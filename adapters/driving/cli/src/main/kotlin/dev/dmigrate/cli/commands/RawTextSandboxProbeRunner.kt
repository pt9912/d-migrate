package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.MigrateConfigResolver
import dev.dmigrate.core.diff.RawTextServerForm
import dev.dmigrate.core.diff.migration.overlay.RawTextProvenance
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.RawTextSandboxResult
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.postgresql.PostgresRawTextSandbox
import java.nio.file.Path

/**
 * CLI-Verdrahtung des Wegwerf-Sandkastens.
 *
 * Drei Bedingungen, alle drei notwendig: der Anwender hat ihn eingeschaltet
 * (`migrate.raw_sql_sandbox`), der Dialekt traegt ihn
 * ([DialectCapabilities.supportsRawTextSandbox]), und das Soll liess sich dort
 * anwenden. Faellt eine aus, gibt es keine Serverform — und der Vergleich
 * entscheidet am Text, also konservativ.
 *
 * Verbindungsaufbau wie in [CheckPreflightProbeRunner].
 */
internal object RawTextSandboxProbeRunner {

    fun probe(
        target: CompareOperand.Database,
        configPath: Path?,
        desired: SchemaDefinition,
        dialect: DatabaseDialect,
    ): RawTextServerForm? {
        if (MigrateConfigResolver(configPathFromCli = configPath).resolve().rawSqlSandbox != true) return null
        if (!DialectCapabilities.forDialect(dialect).supportsRawTextSandbox) return null
        val sandbox = sandboxFor(dialect) ?: return null

        val url = try {
            dev.dmigrate.cli.config.NamedConnectionResolver(configPathFromCli = configPath).resolve(target.source)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "Config resolution failed", e)
        }
        val config = try {
            CredentialFilling(target.source).fill(url)
        } catch (e: Exception) {
            throw CompareConfigException(e.message ?: "URL parse failed", e)
        }
        val result = HikariConnectionPoolFactory.create(config).use { pool -> sandbox.deparse(desired, pool) }
        return when (result) {
            is RawTextSandboxResult.Deparsed -> serverFormOf(desired, result.schema)
            is RawTextSandboxResult.Unavailable -> null
        }
    }

    /**
     * Die Serverform je Feld — gebaut aus denselben Paaren, die auch ein
     * Herkunfts-Overlay traegt: links der Autorentext, rechts, was der Server
     * daraus macht. Nur stammt das Rechte hier aus dem Sandkasten statt aus
     * einem frueheren Lauf.
     */
    private fun serverFormOf(desired: SchemaDefinition, sandboxed: SchemaDefinition): RawTextServerForm {
        val byId = RawTextProvenance.entriesOf(desired, sandboxed).associateBy { it.id }
        return RawTextServerForm { objectType, objectPath, field, keyPosition ->
            byId[RawTextProvenance.entryId(objectType, objectPath, field, keyPosition)]?.observedCatalogText
        }
    }

    /**
     * Absichtlich ohne `else`: ein sechster Dialekt muss hier eine Entscheidung
     * eintragen. Vier der fuenf haben heute keinen Sandkasten — bei Oracle ist
     * ein Schema ein Benutzer, den ein Migrationsnutzer nicht anlegen darf
     * (`ORA-01031`, gemessen); MySQL, SQL Server und SQLite sind ungemessen und
     * stehen deshalb aus.
     */
    private fun sandboxFor(dialect: DatabaseDialect): dev.dmigrate.driver.RawTextSandboxPort? = when (dialect) {
        DatabaseDialect.POSTGRESQL -> PostgresRawTextSandbox()
        DatabaseDialect.MYSQL,
        DatabaseDialect.SQLITE,
        DatabaseDialect.MSSQL,
        DatabaseDialect.ORACLE,
        -> null
    }
}
