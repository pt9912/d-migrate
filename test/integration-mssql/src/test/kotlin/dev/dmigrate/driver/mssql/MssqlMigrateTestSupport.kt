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

/**
 * Was die Migrate-Specs dieses Moduls gemeinsam brauchen: einen Container mit
 * eigener Datenbank, einen Pool darauf, und die drei Handgriffe, mit denen ein
 * Test den Server befragt.
 *
 * Geteilt statt je Spec kopiert, weil beide Specs denselben Server auf
 * dieselbe Art ansprechen — und weil ein zweiter Satz Kopien beim naechsten
 * Spec wieder waechst.
 */
internal fun startMssqlContainer(): MSSQLServerContainer =
    MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

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
