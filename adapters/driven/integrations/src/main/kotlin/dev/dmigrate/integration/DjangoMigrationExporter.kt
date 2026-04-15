package dev.dmigrate.integration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationDdlPayload
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolExportResult
import dev.dmigrate.migration.ToolMigrationExporter

/**
 * Renders a minimal Django `RunSQL` migration from a [MigrationBundle].
 *
 * Produces exactly one Python file:
 * - `<version>.py`
 *
 * The file uses `migrations.RunSQL` with the ordered statement sequence
 * from `MigrationDdlPayload.result.statements`. No app scaffolding or
 * dependency chain is generated.
 */
class DjangoMigrationExporter : ToolMigrationExporter {

    override val tool = MigrationTool.DJANGO

    override fun render(bundle: MigrationBundle): ToolExportResult {
        val identity = bundle.identity
        val fileName = "${identity.version}.py"

        val python = buildString {
            appendLine("from django.db import migrations")
            appendLine()
            appendLine()
            appendLine("class Migration(migrations.Migration):")
            appendLine()
            appendLine("    dependencies = []")
            appendLine()
            appendLine("    operations = [")
            appendLine("        migrations.RunSQL(")
            appendLine("            sql=\"\"\"")
            append(renderStatements(bundle.up))
            appendLine("\"\"\"" + ",")

            when (val rollback = bundle.rollback) {
                is MigrationRollback.NotRequested -> {}
                is MigrationRollback.Requested -> {
                    appendLine("            reverse_sql=\"\"\"")
                    append(renderStatements(rollback.down))
                    appendLine("\"\"\",")
                }
            }

            appendLine("        ),")
            appendLine("    ]")
        }

        return ToolExportResult(
            artifacts = listOf(
                MigrationArtifact(
                    relativePath = ArtifactRelativePath.of(fileName),
                    kind = "migration",
                    content = python,
                )
            ),
        )
    }

    internal companion object {
        fun renderStatements(payload: MigrationDdlPayload): String = buildString {
            for (statement in payload.result.statements) {
                if (statement.sql.isBlank()) continue
                appendLine(RenderHelpers.escapePython(statement.sql))
            }
        }
    }
}
