package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant

/**
 * Schreibt das `d-migrate.manifest`-Footer-KV
 * (AP11 §5/§6.1, S4 Cut A). Liefert eine Provider-Lambda fuer
 * den [dev.dmigrate.format.parquet.ParquetChunkWriter]-
 * `extraMetaDataProvider`-Konstruktor; das CLI-Wiring (S6)
 * instanziiert die Provider mit der konkreten
 * `producerVersion` und reicht sie an die
 * [dev.dmigrate.format.parquet.ParquetChunkWriterFactory]
 * durch.
 *
 * Der erzeugte YAML ist die konditionell strikte Teilmenge
 * des Bundle-Manifests (AP11 §5.2):
 *
 * - Genau eine Tabelle.
 * - `tables[0].file` bleibt leer (Single-File-KV verweist
 *   nicht auf seine eigene Datei).
 * - `tables[0].sha256` bleibt leer (zirkulaer).
 */
class ParquetSingleFileManifestWriter(
    private val producerVersion: String,
    private val tableNameOverride: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Einmal pro Writer-Instanz gefroren — spiegelt
     * [dev.dmigrate.format.parquet.manifest.ParquetBundleClosure],
     * das `exportedAt` ebenfalls einmal pro Hook-Aufruf capture't.
     */
    private val exportedAt: Instant = Instant.now(clock)

    /**
     * Provider-Lambda zur Verwendung als
     * [dev.dmigrate.format.parquet.ParquetChunkWriter]-
     * Konstruktor-Argument.
     */
    val provider: (ChunkSchema) -> Map<String, String> = { schema -> build(schema) }

    fun build(schema: ChunkSchema): Map<String, String> {
        val manifest = ParquetBundleManifest(
            formatVersion = ParquetBundleManifest.CURRENT_FORMAT_VERSION,
            producer = ParquetBundleManifest.PRODUCER_LITERAL,
            producerVersion = producerVersion,
            exportedAt = exportedAt,
            schemaSource = ManifestSchemaSource.fromSchemaOrigin(schema.origin),
            tables = listOf(
                ManifestTable(
                    table = tableNameOverride ?: schema.table,
                    file = "",
                    rowCount = null,
                    sha256 = null,
                    columns = ChunkSchemaToManifest.toManifestColumns(schema),
                )
            ),
        )
        val yaml = ByteArrayOutputStream().also { out ->
            ParquetManifestWriter().write(manifest, out)
        }.toByteArray()
        return mapOf(FOOTER_KEY to yaml.decodeToString())
    }

    companion object {
        const val FOOTER_KEY: String = "d-migrate.manifest"
    }
}

/**
 * Liest das `d-migrate.manifest`-Footer-KV (AP11 §5/§6.2,
 * S4 Cut A). Liefert `null`, wenn der Key fehlt — das ist
 * **kein Fehler**, sondern signalisiert dem Preflight, dass
 * der Footer-`MessageType`-Fallback aus AP11 §5.3 greifen
 * muss.
 *
 * Wirft [ParquetManifestParseException] bei vorhandenem,
 * aber kaputtem Manifest (`MANIFEST_PARSE_ERROR`).
 */
class ParquetSingleFileManifestReader {

    /**
     * @return Parsed Manifest oder `null`, wenn der Footer-
     *   KV-Key fehlt. Bewusst `internal`, weil
     *   `ParquetBundleManifest` ein Adapter-internes Datenmodell
     *   ist — Konsumenten ausserhalb des Moduls nutzen
     *   [readSchema], das eine `ChunkSchema` zurueckgibt.
     */
    internal fun read(extraMetaData: Map<String, String>): ParquetBundleManifest? {
        val raw = extraMetaData[ParquetSingleFileManifestWriter.FOOTER_KEY] ?: return null
        return ParquetManifestReader(context = ParquetManifestReader.Context.SINGLE_FILE).read(raw)
    }

    /**
     * Convenience: liefert den [ChunkSchema] aus dem
     * Footer-KV mit `origin = JDBC_METADATA` als Default
     * (Single-File-KV traegt heute keine eigene Origin-
     * Information — AP9 §5 hat fuer Bundle den
     * `MANIFEST_FALLBACK`-Wert eingefuehrt, S4 nutzt ihn
     * nicht).
     */
    fun readSchema(
        extraMetaData: Map<String, String>,
        origin: SchemaOrigin = SchemaOrigin.JDBC_METADATA,
    ): ChunkSchema? {
        val manifest = read(extraMetaData) ?: return null
        val table = manifest.tables.singleOrNull()
            ?: throw ParquetManifestParseException(
                "MANIFEST_FIELD_INVALID: Single-File-Footer-KV must contain exactly one table, " +
                    "got ${manifest.tables.size}",
            )
        return table.toChunkSchema(origin)
    }
}
