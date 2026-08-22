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
        val raw = DdlScript.render(result, dialect)
        val deterministic = GENERATED_TIMESTAMP.replace(raw) { match ->
            match.groupValues[1]
        }
        return MigrationDdlPayload(
            result = result,
            deterministicSql = deterministic,
        )
    }
}
