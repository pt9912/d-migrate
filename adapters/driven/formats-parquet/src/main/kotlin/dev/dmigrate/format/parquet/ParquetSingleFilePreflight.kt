package dev.dmigrate.format.parquet

import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.data.SeekableChunkSource
import dev.dmigrate.format.parquet.manifest.ParquetManifestParseException
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestReader
import dev.dmigrate.format.parquet.manifest.Sha256DigestCalculator
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.MessageType
import org.apache.parquet.schema.Type.Repetition
import java.nio.file.Path

/**
 * Adapter-internes Preflight-Ergebnis fuer den Single-File-
 * Parquet-Pfad (AP11 §6.2 / §7.1, S4 Cut A). Wird vom
 * CLI-Resolver (S6) an der Port-Grenze in
 * `ResolvedTableInput.Seekable` uebersetzt; der
 * `StreamingImporter`/`TableImporter` sieht keinen
 * Parquet-Sonderpfad.
 *
 * - [path]: absolute, normalisierte Datei-Path.
 * - [table]: aufgeloester Tabellenname (AP11 §5.5 Precedence).
 * - [schema]: bereits aufgeloestes [ChunkSchema] aus dem
 *   Footer-KV oder dem Phase-2-Fallback.
 * - [contentSha256]: SHA-256 ueber den vollstaendigen
 *   Datei-Bytestrom (AP11 §6.4) — fuer Resume-Check; `null`
 *   wenn die Phase-1 ohne Resume-Aktivierung lief.
 * - [manifestPresent]: `true`, wenn der Footer-KV
 *   `d-migrate.manifest`-Key enthielt; `false` bedeutet, dass
 *   [schema] aus dem Footer-`MessageType` abgeleitet wurde.
 *   Das ist fuer das Lesen ausreichend, aber aermer: Varianten,
 *   die sich dieselbe Physik teilen (`Identifier` vs. `Integer`,
 *   `Geometry` vs. `Binary`, `Enum`/`Char` vs. `Text`), sind
 *   dann nicht unterscheidbar. **Produktivcode wertet das Feld
 *   heute nicht aus** — es steht fuer Diagnose und Tests.
 */
data class ResolvedParquetSingleFile(
    val path: Path,
    val table: String,
    val schema: ChunkSchema,
    val contentSha256: String?,
    val manifestPresent: Boolean,
)

/**
 * Two-Phase-Preflight fuer Single-File-Parquet-Imports
 * (AP11 §6.2, S4 Cut A).
 *
 * - [phase1] laeuft **vor** dem DB-Connect: oeffnet den
 *   Parquet-Footer, parst `d-migrate.manifest` (oder faengt
 *   den Footer-`MessageType`-Fallback), validiert
 *   Tabellennamens-Precedence (AP11 §5.5).
 * - [phase2] laeuft **nach** dem DB-Connect: prueft den
 *   Inhalts-Hash, wenn Resume aktiv ist (AP11 §6.4).
 *
 * Beide Phasen sind reine Funktionen; das CLI-Wiring ruft sie
 * nacheinander auf.
 *
 * Ein frueher vorgesehener Target-JDBC-Schema-Fallback in
 * [phase2] ist **entfallen**: Die Typen kommen jetzt aus dem
 * Footer der Datei selbst. Aus dem Ziel abzuleiten waere
 * schwaecher — es setzt eine existierende Zieltabelle voraus
 * und liegt bei abweichender Spaltenreihenfolge still daneben.
 */
class ParquetSingleFilePreflight {

    /**
     * Phase 1 — vor DB-Connect.
     *
     * @param path Quelldatei (existiert, ist regulaere Datei).
     * @param explicitTable CLI-`--table` Wert, falls gesetzt.
     * @param computeContentSha256 Wenn `true`, wird der
     *   Datei-Hash sofort berechnet (Resume aktiv). Andernfalls
     *   liefert das DTO `contentSha256 = null`.
     */
    fun phase1(
        path: Path,
        explicitTable: String?,
        computeContentSha256: Boolean = false,
    ): ResolvedParquetSingleFile {
        val configuration = Configuration(false)
        val inputFile = HadoopInputFile.fromPath(HadoopPath(path.toUri()), configuration)
        val (manifestSchema, footerSchema, extraMetaData) = ParquetFileReader.open(inputFile).use { reader ->
            val meta = reader.fileMetaData
            Triple(
                ParquetSingleFileManifestReader().readSchema(meta.keyValueMetaData ?: emptyMap()),
                meta.schema,
                meta.keyValueMetaData ?: emptyMap(),
            )
        }

        val resolvedTable = resolveTableName(explicitTable, manifestSchema?.table)
        val manifestPresent = extraMetaData.containsKey(
            dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter.FOOTER_KEY,
        )

        val schema = manifestSchema?.let {
            // CLI-Override-Tabellenname uebernehmen (AP11 §5.5 Punkt 1).
            if (it.table == resolvedTable) it else it.copy(table = resolvedTable)
        } ?: buildSchemaFromFooter(resolvedTable, footerSchema)

        val contentSha256 = if (computeContentSha256) Sha256DigestCalculator.compute(path) else null
        return ResolvedParquetSingleFile(
            path = path.toAbsolutePath().normalize(),
            table = resolvedTable,
            schema = schema,
            contentSha256 = contentSha256,
            manifestPresent = manifestPresent,
        )
    }

    /**
     * Phase 2 — nach DB-Connect.
     *
     * Nur Hash-Konsistenz-Check fuer Resume, sonst
     * Pass-Through. Eine Schema-Verbesserung findet hier
     * bewusst NICHT mehr statt — [phase1] liefert bereits
     * Typen aus dem Footer.
     *
     * @param phase1 Phase-1-Ergebnis.
     * @param resumeExpectedSha256 Erwarteter Inhalts-Hash
     *   aus dem Checkpoint (AP11 §6.4); wenn nicht-null,
     *   wird er gegen `phase1.contentSha256` verglichen.
     * @throws ParquetSingleFileResumeException bei Hash-
     *   Mismatch.
     */
    fun phase2(
        phase1: ResolvedParquetSingleFile,
        resumeExpectedSha256: String? = null,
    ): ResolvedParquetSingleFile {
        if (resumeExpectedSha256 != null) {
            val actual = phase1.contentSha256
                ?: throw ParquetSingleFileResumeException(
                    "PARQUET_SINGLE_FILE_CHECKPOINT_REQUIRES_HASH: resume requested but phase1 " +
                        "did not compute contentSha256",
                )
            if (actual != resumeExpectedSha256) {
                throw ParquetSingleFileResumeException(
                    "PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT: " +
                        "expected=$resumeExpectedSha256, actual=$actual",
                )
            }
        }
        return phase1
    }

    private fun resolveTableName(explicit: String?, fromManifest: String?): String {
        return when {
            explicit != null && fromManifest != null && explicit != fromManifest ->
                throw ParquetSingleFileTableMismatchException(
                    "PARQUET_SINGLE_FILE_TABLE_MISMATCH: " +
                        "explicit '$explicit' vs manifest '$fromManifest'",
                )
            explicit != null -> explicit
            fromManifest != null -> fromManifest
            else -> throw ParquetSingleFileTableRequiredException(
                "PARQUET_SINGLE_FILE_TABLE_REQUIRED: specify --table or export with the " +
                    "d-migrate parquet writer to embed the table name",
            )
        }
    }

    private fun buildSchemaFromFooter(table: String, footer: MessageType): ChunkSchema {
        val columns = footer.fields.map { field ->
            ChunkColumnSchema(
                name = field.name,
                nullable = field.repetition != Repetition.REQUIRED,
                // Der Typ kommt aus dem Footer, nicht aus einem Platzhalter.
                // Vorher stand hier durchgehend `Text` als Marker, den ein
                // spaeterer Schritt ueber das Ziel-JDBC-Schema ersetzen sollte;
                // der Schritt kam nie, und der Marker lief ungefiltert in
                // ParquetGroupValueReader.readColumn, das daraufhin getString
                // auf einer INT32-Spalte aufrief (ClassCastException).
                //
                // Die Datei traegt ihr Schema selbst — dieselbe `field`-Instanz,
                // aus der Name und Nullability stammen. Es aus dem Ziel
                // abzuleiten waere schwaecher: das setzt eine existierende
                // Zieltabelle voraus und liegt bei abweichender
                // Spaltenreihenfolge still daneben, statt laut zu scheitern.
                neutralType = ParquetMessageTypeToChunkSchema.neutralTypeOf(field.asPrimitiveType()),
            )
        }
        return ChunkSchema(
            table = table,
            origin = SchemaOrigin.MANIFEST_FALLBACK,
            columns = columns,
        )
    }
}

/**
 * Wirft, wenn die Phase-2 einen Hash-Mismatch oder
 * fehlenden Hash gegen den Checkpoint feststellt (AP11
 * §6.4 Fehlerklassen).
 */
class ParquetSingleFileResumeException(message: String) : RuntimeException(message)

/**
 * Wirft, wenn `--table` und Footer-KV einen anderen
 * Tabellennamen tragen (AP11 §5.5 Punkt 1).
 */
class ParquetSingleFileTableMismatchException(message: String) : RuntimeException(message)

/**
 * Wirft, wenn weder `--table` noch Footer-KV einen
 * Tabellennamen liefern (AP11 §5.5 Punkt 3).
 */
class ParquetSingleFileTableRequiredException(message: String) : RuntimeException(message)

/**
 * Erweiterte Helper-Funktion, die das in S6 erwartete
 * `ResolvedTableInput.Seekable`-Output erzeugt — bewusst
 * hier statt im Streaming-Modul, damit `:adapters:driven:streaming`
 * weiterhin parquet-frei bleibt (AP11 §7.2).
 *
 * Aufruf: `phase2(phase1(...)).toSeekableInput()` im
 * CLI-Resolver.
 */
fun ResolvedParquetSingleFile.toSeekableSource(): SeekableChunkSource =
    SeekableChunkSource.Local(path)

