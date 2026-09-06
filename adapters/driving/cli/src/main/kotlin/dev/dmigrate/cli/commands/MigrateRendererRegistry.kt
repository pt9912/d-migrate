package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.mssql.MssqlDiffDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDiffDdlGenerator
import dev.dmigrate.driver.oracle.OracleDiffDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDiffDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDiffDdlGenerator

/**
 * Wiring-side renderer lookup for the `schema migrate` command.
 * Direct instantiation rather than ServiceLoader because the
 * renderers are stateless and cheap, and the CLI module already depends
 * on every driver adapter.
 *
 * Der Rueckgabetyp bleibt nullable, obwohl heute jeder Dialekt einen
 * Renderer hat: `null` nimmt den bestehenden "No renderer registered"-
 * Exit-2-Pfad der `SchemaMigratePreparation`, und das ist die richtige
 * Antwort fuer den naechsten Dialekt, der hinzukommt, bevor sein
 * Renderer steht.
 */
internal object MigrateRendererRegistry {

    fun forDialect(dialect: DatabaseDialect): DiffDdlGenerator? = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresDiffDdlGenerator()
        DatabaseDialect.MYSQL -> MysqlDiffDdlGenerator()
        DatabaseDialect.SQLITE -> SqliteDiffDdlGenerator()
        DatabaseDialect.MSSQL -> MssqlDiffDdlGenerator()
        DatabaseDialect.ORACLE -> OracleDiffDdlGenerator()
    }
}
