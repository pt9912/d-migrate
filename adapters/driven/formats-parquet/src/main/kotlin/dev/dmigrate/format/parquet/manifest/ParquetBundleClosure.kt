package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.BundleClosureContext
import dev.dmigrate.streaming.BundleClosureTable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

/**
 * Hook-Implementierung fuer den
 * [dev.dmigrate.streaming.StreamingExporter]
 * Bundle-Closure-Pfad (S3b Cut A, AP7 §10.1).
 *
 * Schreibt `manifest.yaml` ins Bundle-Wurzelverzeichnis,
 * sobald alle Parquet-Dateien geschrieben sind. Inhalt
 * deckt AP7 §5 Pflichtfelder ab; optionale SHA-256-Werte
 * werden pro Tabelle berechnet, wenn `manifestSha256` true
 * ist (CLI-Flag `--manifest-sha256` in AP12 §4).
 *
 * Der Hook ignoriert Aufrufe fuer Nicht-Parquet-Formate —
 * `JSON`/`YAML`/`CSV`-Bundles haben kein Manifest.
 */
class ParquetBundleClosure(
    private val producerVersion: String,
    private val manifestSha256: Boolean = false,
    private val clock: Clock = Clock.systemUTC(),
) {

    operator fun invoke(context: BundleClosureContext) {
        if (context.format != DataExportFormat.PARQUET) return
        val manifest = ParquetBundleManifest(
            formatVersion = ParquetBundleManifest.CURRENT_FORMAT_VERSION,
            producer = ParquetBundleManifest.PRODUCER_LITERAL,
            producerVersion = producerVersion,
            exportedAt = Instant.now(clock),
            schemaSource = inferSchemaSource(context),
            tables = context.tables.map { table -> toManifestTable(table, context.directory) },
        )
        val manifestPath = context.directory.resolve(MANIFEST_FILE_NAME)
        Files.newOutputStream(manifestPath).use { out ->
            ParquetManifestWriter().write(manifest, out)
        }
    }

    private fun inferSchemaSource(context: BundleClosureContext): ManifestSchemaSource {
        val firstSchema = context.tables.firstOrNull { it.schema != null }?.schema
        return firstSchema?.let { ManifestSchemaSource.fromSchemaOrigin(it.origin) }
            ?: ManifestSchemaSource.MANIFEST_FALLBACK
    }

    private fun toManifestTable(table: BundleClosureTable, bundleDir: Path): ManifestTable {
        val relativeFile = bundleDir.toAbsolutePath().normalize().relativize(
            table.file.toAbsolutePath().normalize()
        ).toString()
        val sha = if (manifestSha256) Sha256DigestCalculator.compute(table.file) else null
        val columns = table.schema?.let { ChunkSchemaToManifest.toManifestColumns(it) }
            ?: emptyList()
        return ManifestTable(
            table = table.table,
            file = relativeFile,
            rowCount = table.rowCount,
            sha256 = sha,
            columns = columns,
        )
    }

    companion object {
        const val MANIFEST_FILE_NAME: String = "manifest.yaml"
    }
}
