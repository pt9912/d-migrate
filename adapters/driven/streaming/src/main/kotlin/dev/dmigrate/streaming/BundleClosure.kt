package dev.dmigrate.streaming

import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import java.nio.file.Path

/**
 * Hook-Argument fuer den Bundle-Closure-Pfad des
 * [StreamingExporter] (Parquet Cut A S3b, AP7 §10.1 /
 * Umbrella §3 S3b).
 *
 * Wird nach Abschluss aller Tabellen einer
 * [ExportOutput.FilePerTable]-Operation aufgerufen, damit
 * Format-Adapter (heute nur Parquet) Bundle-weite Artefakte
 * schreiben koennen — z.B. `manifest.yaml` mit
 * `producer`/`exportedAt`/`tables[].columns`-Metadaten.
 *
 * `Stdout` und `SingleFile` rufen den Hook **nicht** auf —
 * sie haben kein Bundle.
 */
data class BundleClosureContext(
    val directory: Path,
    val format: DataExportFormat,
    val tables: List<BundleClosureTable>,
)

/**
 * Pro-Tabelle-Information fuer den Bundle-Closure-Hook.
 * `file` ist der absolute Pfad zur geschriebenen Datei
 * (z.B. `directory/<table>.parquet`); `schema` ist das vom
 * Reader gelieferte [ChunkSchema] (oder `null` bei leeren
 * Tabellen, die kein Schema gesehen haben).
 */
data class BundleClosureTable(
    val table: String,
    val file: Path,
    val schema: ChunkSchema?,
    val rowCount: Long,
)
