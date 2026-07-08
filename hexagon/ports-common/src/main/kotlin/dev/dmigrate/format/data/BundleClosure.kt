package dev.dmigrate.format.data

import java.nio.file.Path

/**
 * Hook argument for bundle-wide export finalization, e.g. Parquet
 * manifest.yaml generation after all table files were written.
 */
data class BundleClosureContext(
    val directory: Path,
    val format: DataExportFormat,
    val tables: List<BundleClosureTable>,
)

/**
 * Per-table information for the bundle-closure hook.
 */
data class BundleClosureTable(
    val table: String,
    val file: Path,
    val schema: ChunkSchema?,
    val rowCount: Long,
)
