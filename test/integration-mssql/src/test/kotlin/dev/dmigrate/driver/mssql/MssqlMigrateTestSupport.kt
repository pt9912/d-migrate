package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.DiffDdlGenerator
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager
import java.time.Duration

/**
 * Was die Migrate-Specs dieses Moduls gemeinsam brauchen: einen Container mit
 * eigener Datenbank, einen Pool darauf, und die drei Handgriffe, mit denen ein
 * Test den Server befragt.
 *
 * Geteilt statt je Spec kopiert, weil beide Specs denselben Server auf
 * dieselbe Art ansprechen — und weil ein zweiter Satz Kopien beim naechsten
 * Spec wieder waechst.
 */
/**
 * Wie lange ein SQL-Server-Container zum Hochfahren bekommt.
 *
 * Gebraucht, seit die Suiten gegen 2025 laufen: der Container startete in CI,
 * nahm aber innerhalb der Testcontainers-Vorgabe noch keine Verbindungen an
 * (`Connection refused`), waehrend vier weitere Container desselben Laufs
 * durchkamen. Es ist also keine Eigenschaft des Images, sondern der Last —
 * und dagegen hilft Zeit, nicht Raten. Oracle bekommt hier aus demselben
 * Grund schon laenger fuenf Minuten.
 */
internal val MSSQL_STARTUP_TIMEOUT: Duration = Duration.ofMinutes(5)

internal fun startMssqlContainer(): MSSQLServerContainer =
    MSSQLServerContainer("mcr.microsoft.com/mssql/server:2025-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")
        .withStartupTimeout(MSSQL_STARTUP_TIMEOUT)

/** Legt [database] auf dem laufenden Container an und oeffnet einen Pool darauf. */
internal fun poolFor(container: MSSQLServerContainer, database: String): ConnectionPool {
    DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
        conn.createStatement().use { it.execute("CREATE DATABASE $database") }
    }
    return HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = database,
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        ),
    )
}

internal fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }
}

internal fun readSchema(pool: ConnectionPool): SchemaDefinition = MssqlSchemaReader().read(pool).schema

internal fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-mssql",
    schema = readSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.MSSQL,
)

internal fun noRenderer(): DiffDdlGenerator = error("test wires only the MSSQL renderer")
