package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDiffDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDiffDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDiffDdlGenerator

/**
 * Wiring-side renderer lookup for the `schema migrate` command.
 * Direct instantiation rather than ServiceLoader because the
 * Phase D renderers are stateless and cheap, and the CLI module
 * already depends on all three driver adapters.
 */
internal object MigrateRendererRegistry {

    fun forDialect(dialect: DatabaseDialect): DiffDdlGenerator? = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresDiffDdlGenerator()
        DatabaseDialect.MYSQL -> MysqlDiffDdlGenerator()
        DatabaseDialect.SQLITE -> SqliteDiffDdlGenerator()
    }
}
