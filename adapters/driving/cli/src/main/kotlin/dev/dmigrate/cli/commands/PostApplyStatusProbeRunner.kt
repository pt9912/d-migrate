package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.migration.UncompiledObject
import dev.dmigrate.driver.oracle.OraclePostApplyStatusProbe
import java.nio.file.Path

/**
 * CLI-Verdrahtung der Nachfrage nach dem Anwenden. Verbindungsaufbau wie in
 * [CheckPreflightProbeRunner]; die Auswahl je Dialekt steht in [dispatch].
 */
internal object PostApplyStatusProbeRunner {

    fun probe(
        target: CompareOperand.Database,
        configPath: Path?,
        plan: DiffResult,
        dialect: DatabaseDialect,
    ): List<UncompiledObject> {
        if (!hasProbe(dialect)) return emptyList()
        val url = try {
            NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            CredentialFilling(target.source).fill(url)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        val pool = HikariConnectionPoolFactory.create(config)
        return pool.use { p ->
            p.borrow().use { conn -> dispatch(conn, dialect, plan) }
        }
    }

    /**
     * Ob der Dialekt ueberhaupt eine Nachfrage kennt — geprueft, bevor eine
     * Verbindung aufgebaut wird: fuer vier der fuenf waere sie umsonst.
     */
    private fun hasProbe(dialect: DatabaseDialect): Boolean = dialect == DatabaseDialect.ORACLE

    /**
     * Auswahl je Dialekt, absichtlich ohne `else`: ein sechster Dialekt muss
     * hier eine Entscheidung eintragen, der Compiler erzwingt es.
     *
     * Vier der fuenf liefern nichts, und das ist keine Luecke, sondern ihr
     * Verhalten: PostgreSQL, MySQL, SQLite und SQL Server fuehren keinen
     * Zustand „angelegt, aber nicht uebersetzt". Wo ein Rumpf erst beim Aufruf
     * scheitert (PostgreSQL mit `check_function_bodies`, SQL Server mit
     * aufgeschobener Namensaufloesung), gibt es dafuer auch keinen
     * Katalogeintrag, den man nachschlagen koennte.
     */
    internal fun dispatch(
        connection: DatabaseConnection,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): List<UncompiledObject> = when (dialect) {
        DatabaseDialect.ORACLE -> OraclePostApplyStatusProbe.probe(connection, plan)
        DatabaseDialect.POSTGRESQL,
        DatabaseDialect.MYSQL,
        DatabaseDialect.SQLITE,
        DatabaseDialect.MSSQL,
        -> emptyList()
    }
}
