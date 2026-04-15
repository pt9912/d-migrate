package dev.dmigrate.integration

import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationIdentity
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolExportResult
import dev.dmigrate.migration.ToolMigrationExporter

/**
 * Renders a Liquibase-compatible XML changelog from a [MigrationBundle].
 *
 * Produces exactly one versioned XML file:
 * - `changelog-<version>-<slug>.xml`
 *
 * The file contains exactly one `<changeSet>` with embedded SQL and an
 * optional `<rollback>` block. No master changelog is generated or mutated.
 */
class LiquibaseMigrationExporter : ToolMigrationExporter {

    override val tool = MigrationTool.LIQUIBASE

    override fun render(bundle: MigrationBundle): ToolExportResult {
        val identity = bundle.identity
        val changeSetId = deriveChangeSetId(identity)
        val fileName = "changelog-${identity.version}-${identity.slug}.xml"

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<databaseChangeLog""")
            appendLine("""    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"""")
            appendLine("""    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
            appendLine("""    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog""")
            appendLine("""        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">""")
            appendLine()
            appendLine("""    <changeSet id="$changeSetId" author="d-migrate">""")
            appendLine("""        <sql>""")
            append(indentSql(RenderHelpers.escapeXml(bundle.up.deterministicSql)))
            appendLine()
            appendLine("""        </sql>""")

            when (val rollback = bundle.rollback) {
                is MigrationRollback.NotRequested -> {}
                is MigrationRollback.Requested -> {
                    appendLine("""        <rollback>""")
                    appendLine("""            <sql>""")
                    append(indentSql(RenderHelpers.escapeXml(rollback.down.deterministicSql), 16))
                    appendLine()
                    appendLine("""            </sql>""")
                    appendLine("""        </rollback>""")
                }
            }

            appendLine("""    </changeSet>""")
            appendLine()
            appendLine("""</databaseChangeLog>""")
        }

        return ToolExportResult(
            artifacts = listOf(
                MigrationArtifact(
                    relativePath = ArtifactRelativePath.of(fileName),
                    kind = "changelog",
                    content = xml,
                )
            ),
        )
    }

    internal companion object {
        fun deriveChangeSetId(identity: MigrationIdentity): String =
            "${identity.version}-${identity.slug}-${identity.dialect.name.lowercase()}"

        fun indentSql(sql: String, indent: Int = 12): String {
            val prefix = " ".repeat(indent)
            return sql.lines().joinToString("\n") { line ->
                if (line.isBlank()) line else "$prefix$line"
            }
        }
    }
}
