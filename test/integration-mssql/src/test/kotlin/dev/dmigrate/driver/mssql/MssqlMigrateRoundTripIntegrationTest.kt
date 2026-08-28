package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.DiffDdlGenerator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory

/**
 * Round-Trip-Beleg fuer `schema migrate` gegen echtes SQL Server — das
 * MSSQL-Gegenstueck zu den Smokes der drei anderen Dialekte.
 *
 * Gefahren werden die ECHTEN Runner, nicht der Renderer allein:
 *
 * 1. Ausgangsschema in der Datenbank einrichten,
 * 2. `schema migrate --execute --generate-rollback` (der Post-Compare des
 *    Runners muss selbst durchgehen → Exit 0, Artefakt geschrieben),
 * 3. **unabhaengig** zurueckliesen und den Inhalts-Fingerprint gegen das
 *    Soll-Schema pruefen — die Gegenprobe zum Post-Compare des Runners,
 * 4. `schema rollback --execute --allow-destructive` mit dem Artefakt aus 2,
 * 5. unabhaengig zurueckliesen und gegen das Ausgangsschema pruefen.
 *
 * Die Aenderung ist bewusst eine, an der mehrere T-SQL-Eigenheiten haengen:
 * eine hinzugefuegte Spalte MIT Default. Der Default ist in SQL Server ein
 * eigenes benanntes Objekt, und der Rollback muss ihn wieder loesen, bevor
 * er die Spalte entfernen kann.
 */
class MssqlMigrateRoundTripIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var config: ConnectionConfig
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE dmigrate_roundtrip") }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_roundtrip",
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        )
        pool = HikariConnectionPoolFactory.create(config)
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("AddColumn round-trip leaves the database exactly as it started") {
        val tmp = createTempDirectory("mssql-roundtrip")
        try {
            execDdl(
                pool,
                "CREATE TABLE round_trip (id BIGINT NOT NULL CONSTRAINT pk_round_trip PRIMARY KEY, " +
                    "name NVARCHAR(100) NOT NULL)",
            )

            val original = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )
            val desired = SchemaDefinition(
                name = "rt-desired",
                version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                            "email" to ColumnDefinition(NeutralType.Text(maxLength = 200)),
                        ),
                        primaryKey = listOf("id"),
                    ),
                ),
            )

            // Vorbedingung: die Datenbank IST das Ausgangsschema.
            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(original)

            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")
            val migrateExit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                // Wie die CLI: der zieldialekt-bewusste Vergleich unterdrueckt
                // Unterschiede, die der Reverse gar nicht ausdruecken kann —
                // etwa das PK-implizite `required`, das SQL Server (wie MySQL)
                // an der Spalte NICHT meldet. Ohne diese Naht plante der Lauf
                // eine Nullability-Aenderung auf dem Schluessel, samt Loesen
                // und Neuanlegen des Primaerschluessels.
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            migrateExit shouldBe 0
            Files.exists(rollbackPath) shouldBe true
            val reportText = Files.readString(reportPath)
            reportText shouldContain "status=ok"
            reportText shouldContain "executionError=null"

            // Gegenprobe zum Post-Compare des Runners.
            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(desired)

            val rollbackExit = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand(pool) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            ).execute(
                SchemaRollbackRequest(
                    source = rollbackPath,
                    target = "db:placeholder",
                    execute = true,
                    allowDestructive = true,
                ),
            )
            rollbackExit shouldBe 0

            fingerprintOf(readSchema(pool)) shouldBe fingerprintOf(original)
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS round_trip")
            tmp.toFile().deleteRecursively()
        }
    }
})

private fun noRenderer(): DiffDdlGenerator = error("test wires only the MSSQL renderer")

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }
}

private fun readSchema(pool: ConnectionPool): SchemaDefinition = MssqlSchemaReader().read(pool).schema

/** Derselbe Kanonisierer, den der Migrate-Pfad fuer diesen Dialekt waehlt. */
private fun fingerprintOf(schema: SchemaDefinition) = MigrationFingerprint.compute(
    schema,
    // Benannt statt als Trailing-Lambda: `compute` traegt seit v9 eine zweite
    // Projektion, und ein nachgestelltes `{ … }` bezoege sich auf die letzte.
    canonicalizeType = { type ->
        MssqlDriver().typeCanonicalizer().canonicalize(type, schema.customTypes)
    },
)

private fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-mssql",
    schema = readSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.MSSQL,
)
