package dev.dmigrate.integration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolExportNote
import dev.dmigrate.migration.ToolExportResult
import dev.dmigrate.migration.ToolExportSeverity
import dev.dmigrate.migration.ToolMigrationExporter

/**
 * Renders Flyway-compatible SQL migration artifacts from a [MigrationBundle].
 *
 * Produces:
 * - `V<version>__<slug>.sql` (up migration)
 * - `U<version>__<slug>.sql` (undo migration, if rollback requested)
 *
 * No Flyway configuration files or project structure are generated.
 */
class FlywayMigrationExporter : ToolMigrationExporter {

    override val tool = MigrationTool.FLYWAY

    override fun render(bundle: MigrationBundle): ToolExportResult {
        val artifacts = mutableListOf<MigrationArtifact>()
        val notes = mutableListOf<ToolExportNote>()

        artifacts += MigrationArtifact(
            relativePath = ArtifactRelativePath.of(
                RenderHelpers.flywayFileName(bundle.identity, "V")
            ),
            kind = "up",
            content = bundle.up.deterministicSql + "\n",
        )

        when (val rollback = bundle.rollback) {
            is MigrationRollback.NotRequested -> {}
            is MigrationRollback.Requested -> {
                artifacts += MigrationArtifact(
                    relativePath = ArtifactRelativePath.of(
                        RenderHelpers.flywayFileName(bundle.identity, "U")
                    ),
                    kind = "undo",
                    content = rollback.down.deterministicSql + "\n",
                )
                notes += ToolExportNote(
                    severity = ToolExportSeverity.INFO,
                    code = "TE-FW-001",
                    message = "Flyway Undo requires Flyway Teams or Enterprise edition",
                    hint = "Community edition does not support U-prefix undo migrations",
                )
            }
        }

        return ToolExportResult(artifacts, notes)
    }
}
