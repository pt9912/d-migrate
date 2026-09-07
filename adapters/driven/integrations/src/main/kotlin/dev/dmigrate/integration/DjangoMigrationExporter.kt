package dev.dmigrate.integration

import dev.dmigrate.driver.DatabaseDialect
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
        // Die Listenform fuehrt jede Anweisung einzeln aus. SQL Server braucht
        // sie, weil `CREATE VIEW`/Routinen allein im Batch stehen muessen; ein
        // PL/SQL-Block braucht sie, weil er selbst Semikola traegt und Django
        // den einen Text sonst als eine Anweisung sendet.
        val statementList = identity.dialect == DatabaseDialect.MSSQL ||
            bundle.up.result.statements.any { it.scriptTerminator != null } ||
            (bundle.rollback as? MigrationRollback.Requested)
                ?.down?.result?.statements?.any { it.scriptTerminator != null } == true

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
            if (statementList) {
                // SQL Server: jedes Statement als eigener RunSQL-Eintrag, damit Django
                // sie einzeln ausfuehrt (CREATE VIEW/Routinen muessen allein im Batch stehen).
                appendLine("            sql=[")
                append(renderStatementList(bundle.up))
                appendLine("            ],")
            } else {
                appendLine("            sql=\"\"\"")
                append(renderStatements(bundle.up))
                appendLine("\"\"\"" + ",")
            }

            when (val rollback = bundle.rollback) {
                is MigrationRollback.NotRequested -> {}
                is MigrationRollback.Requested -> {
                    if (statementList) {
                        appendLine("            reverse_sql=[")
                        append(renderStatementList(rollback.down))
                        appendLine("            ],")
                    } else {
                        appendLine("            reverse_sql=\"\"\"")
                        append(renderStatements(rollback.down))
                        appendLine("\"\"\",")
                    }
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
        private val COMMENT_ONLY = Regex("^(\\s*--[^\n]*\n?)*$")

        fun renderStatementList(payload: MigrationDdlPayload): String = buildString {
            for (statement in payload.result.statements) {
                if (statement.sql.isBlank()) continue
                if (COMMENT_ONLY.matches(statement.sql)) continue
                appendLine("                \"\"\"" + RenderHelpers.escapePython(statement.sql) + "\"\"\",")
            }
        }

        fun renderStatements(payload: MigrationDdlPayload): String = buildString {
            for (statement in payload.result.statements) {
                if (statement.sql.isBlank()) continue
                if (COMMENT_ONLY.matches(statement.sql)) continue
                appendLine(RenderHelpers.escapePython(statement.sql))
            }
        }
    }
}
