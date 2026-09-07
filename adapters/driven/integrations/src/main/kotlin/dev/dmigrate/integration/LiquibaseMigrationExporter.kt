package dev.dmigrate.integration

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationDdlPayload
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
        // T-SQL-Skripte tragen `GO`-Batch-Trenner (DdlScript); Liquibase trennt dann
        // an `GO` statt an `;`, sodass jedes Statement seinen eigenen Batch behaelt.
        val sqlAttributes = if (identity.dialect == DatabaseDialect.MSSQL) """ endDelimiter="GO"""" else ""

        val xml = buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<databaseChangeLog""")
            appendLine("""    xmlns="http://www.liquibase.org/xml/ns/dbchangelog"""")
            appendLine("""    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
            appendLine("""    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog""")
            appendLine("""        http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">""")
            appendLine()
            appendLine("""    <changeSet id="${RenderHelpers.escapeXmlAttribute(changeSetId)}" author="d-migrate">""")
            append(sqlBlocks(bundle.up, sqlAttributes, indent = 8))

            when (val rollback = bundle.rollback) {
                is MigrationRollback.NotRequested -> {}
                is MigrationRollback.Requested -> {
                    appendLine("""        <rollback>""")
                    append(sqlBlocks(rollback.down, sqlAttributes, indent = 12))
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

    /**
     * Die `<sql>`-Elemente eines Abschnitts.
     *
     * Normalerweise **ein** Element mit dem ganzen Skript darin; Liquibase
     * zerlegt es an `;` (bei T-SQL an `GO`) und schickt jede Anweisung
     * einzeln.
     *
     * Enthaelt das Skript aber einen Block, der seinen eigenen Trenner traegt
     * — ein PL/SQL-Rumpf fuehrt Semikola, an denen der Splitter ihn
     * zerschnitte —, dann bekommt **jede** Anweisung ihr eigenes Element mit
     * `splitStatements="false"`. Die Grenzen kennt d-migrate; Liquibase muss
     * sie dann nicht raten.
     */
    private fun sqlBlocks(payload: MigrationDdlPayload, attributes: String, indent: Int): String {
        val pad = " ".repeat(indent)
        if (payload.result.statements.none { it.scriptTerminator != null }) {
            return buildString {
                appendLine("$pad<sql$attributes>")
                appendLine(indentSql(RenderHelpers.escapeXml(payload.deterministicSql), indent + 4))
                appendLine("$pad</sql>")
            }
        }
        return payload.deterministicStatements
            .filter { it.isNotBlank() }
            .joinToString("") { statement ->
                buildString {
                    appendLine("""$pad<sql splitStatements="false">""")
                    appendLine(indentSql(RenderHelpers.escapeXml(statement), indent + 4))
                    appendLine("$pad</sql>")
                }
            }
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
