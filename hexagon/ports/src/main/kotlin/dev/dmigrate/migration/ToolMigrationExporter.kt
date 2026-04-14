package dev.dmigrate.migration

/**
 * Port: renders tool-specific migration artifacts from a [MigrationBundle].
 *
 * Implementations live in the adapter layer (e.g., `adapters/driven/integrations`).
 * The port is side-effect-free: it produces [ToolExportResult] with relative
 * artifact paths and content. Filesystem writing is handled by the
 * application/CLI layer.
 */
interface ToolMigrationExporter {
    val tool: MigrationTool
    fun render(bundle: MigrationBundle): ToolExportResult
}
