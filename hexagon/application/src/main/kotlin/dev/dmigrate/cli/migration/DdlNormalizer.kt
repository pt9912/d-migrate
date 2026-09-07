package dev.dmigrate.cli.migration

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlScript
import dev.dmigrate.migration.MigrationDdlPayload

/**
 * Produces a deterministic SQL representation from a [DdlResult]
 * by stripping the runtime timestamp from the DDL header.
 *
 * The timestamp line (`-- Target: ... | Generated: <ISO-8601>`) is
 * replaced with a stable version that omits the `Generated:` portion.
 * This normalization happens once in the hexagon, not per tool adapter.
 * The SQL is the dialect's **script** rendering ([DdlScript]): for T-SQL
 * it carries `GO` batch separators, which Flyway/sqlcmd require.
 */
object DdlNormalizer {

    private val GENERATED_TIMESTAMP = Regex(
        """^(-- Target: .+?) \| Generated: .+$""",
        RegexOption.MULTILINE,
    )

    fun normalize(result: DdlResult, dialect: DatabaseDialect): MigrationDdlPayload {
        val deterministic = strip(DdlScript.render(result, dialect))
        return MigrationDdlPayload(
            result = result,
            deterministicSql = deterministic,
            // Je Anweisung dieselbe Normalisierung: ein Werkzeug, das die
            // Grenzen braucht, soll sie nicht aus dem Text zurueckraten
            // muessen -- ein PL/SQL-Block traegt Semikola, an denen ein
            // Zeichen-Splitter ihn zerschneiden wuerde.
            deterministicStatements = result.statements.map { strip(DdlScript.renderStatement(it, dialect)) },
        )
    }

    private fun strip(sql: String): String = GENERATED_TIMESTAMP.replace(sql) { it.groupValues[1] }
}
