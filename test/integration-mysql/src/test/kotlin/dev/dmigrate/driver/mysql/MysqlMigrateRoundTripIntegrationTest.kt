package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.connection.asJdbc

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import kotlin.io.path.createTempDirectory


/**
 * F.3 — MySQL round-trip smoke (`docs/planning/done-archive/diffresult-migration-plan.md §F.3`).
 *
 * Mirror of `PostgresMigrateRoundTripIntegrationTest` (F.2) for the
 * first reversible operation matrix per Plan §6.3 (AddColumn /
 * DropColumn). The MySQL-specific deltas vs the PG smoke are:
 *
 * - `MySQLContainer("mysql:8.0")` with `--log-bin-trust-function-creators=1`
 *   and the `allowPublicKeyRetrieval=true` JDBC param (Connector-J
 *   default-auth quirk in container envs).
 * - Reverse-reader sets `required = false` on PK columns (`MysqlSchemaReader.kt:151`)
 *   and always populates `metadata = TableMetadata(engine = "InnoDB")`
 *   (`MysqlSchemaReader.kt:186`); the in-code Soll/Ausgangsschema must
 *   match exactly to keep content fingerprints equal.
 * - `AlterColumnNullability` stays a documented blocker per Plan §11.1;
 *   MySQL `MODIFY COLUMN` needs the full column type, which the standalone
 *   nullability op does not carry. The unit-level renderer contract pins
 *   `MYSQL_NULLABILITY_REQUIRES_COLUMN_TYPE`; this smoke exercises only
 *   AddColumn↔DropColumn.
 *
 * Steps mirror F.2 §1-5: set up Ausgangs-DB, run
 * `schema migrate --execute --generate-rollback`, independent
 * reverse+fingerprint compare against Soll, run
 * `schema rollback --execute --allow-destructive`, independent
 * reverse+fingerprint compare against Ausgangsschema.
 */
class MysqlMigrateRoundTripIntegrationTest : FunSpec({


    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")
        .withCommand("--log-bin-trust-function-creators=1")

    lateinit var config: ConnectionConfig
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
            params = mapOf("allowPublicKeyRetrieval" to "true"),
        )
        pool = HikariConnectionPoolFactory.create(config)
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("a reshaped primary key and a changed UNIQUE are dropped before they are added") {
        val tmp = createTempDirectory("mysql-replace-order")
        try {
            execDdl(
                pool,
                "DROP TABLE IF EXISTS replace_order",
                // Beide Seiten mehrspaltig: einspaltige UNIQUE-Constraints hebt der
                // Reverse auf `column.unique`, daraus wuerden zwei verschiedene
                // Objekte statt eines geaenderten Paares.
                "CREATE TABLE replace_order (id BIGINT NOT NULL, tenant BIGINT NOT NULL, code VARCHAR(50), " +
                    "PRIMARY KEY (id), CONSTRAINT uq_code UNIQUE (code, tenant))",
            )
            val desired = SchemaDefinition(
                name = "replace-order", version = "1",
                tables = mapOf(
                    "replace_order" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "tenant" to ColumnDefinition(NeutralType.BigInteger, required = true),
                            "code" to ColumnDefinition(NeutralType.Text(maxLength = 50)),
                        ),
                        primaryKey = listOf("id", "tenant"),
                        constraints = listOf(ConstraintDefinition(
                            name = "uq_code", type = ConstraintType.UNIQUE, columns = listOf("code", "id"),
                        )),
                    ),
                ),
            )

            val errors = mutableListOf<String>()
            val executed = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                // Wie die CLI: MySQL meldet das PK-implizite `required` an der
                // Spalte nicht. Ohne diese Naht plant der Lauf eine
                // Nullability-Aenderung auf dem Schluessel und blockt sie dann
                // selbst (MYSQL_NULLABILITY_REQUIRES_COLUMN_TYPE).
                targetAwareComparator = { left, right, canonicalize ->
                    SchemaComparator(canonicalize).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MYSQL) MysqlDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MYSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue(
                "ausgefuehrt:\n" + executed.joinToString("\n") +
                    "\nmeldungen:\n" + errors.joinToString("\n") +
                    "\nreport:\n" + java.nio.file.Files.readString(tmp.resolve("report.json")),
            ) { exit shouldBe 0 }

            val dropPk = executed.indexOfFirst { it.contains("DROP PRIMARY KEY") }
            val addPk = executed.indexOfFirst { it.contains("ADD PRIMARY KEY") }
            val dropUq = executed.indexOfFirst { it.contains("DROP") && it.contains("uq_code") }
            val addUq = executed.indexOfFirst { it.contains("ADD") && it.contains("uq_code") }
            withClue("ausgefuehrt:\n" + executed.joinToString("\n")) {
                (dropPk >= 0 && addPk > dropPk) shouldBe true
                (dropUq >= 0 && addUq > dropUq) shouldBe true
            }
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS replace_order")
            tmp.toFile().deleteRecursively()
        }
    }

    test("AddColumn round-trip leaves Ist == Ausgangsschema (content fingerprints match)") {
        val tmp = createTempDirectory("mysql-roundtrip-f3")
        try {
            // ── 1. Ausgangsschema in der DB einrichten ──────────────────
            execDdl(
                pool,
                "DROP TABLE IF EXISTS round_trip",
                "CREATE TABLE round_trip (id BIGINT PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL) ENGINE=InnoDB",
            )

            val originalSchema = SchemaDefinition(
                name = "rt-original",
                version = "0",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = false),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                        ),
                        primaryKey = listOf("id"),
                        metadata = TableMetadata(engine = "InnoDB"),
                    ),
                ),
            )
            val desiredSchema = SchemaDefinition(
                name = "rt-desired",
                version = "1",
                tables = mapOf(
                    "round_trip" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.BigInteger, required = false),
                            "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                            "email" to ColumnDefinition(NeutralType.Text(maxLength = 200)),
                        ),
                        primaryKey = listOf("id"),
                        metadata = TableMetadata(engine = "InnoDB"),
                    ),
                ),
            )

            // Sanity: Ausgangs-DB matches our Ausgangsschema content.
            MigrationFingerprint.compute(readMysqlSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)

            // ── 2. schema migrate --execute --generate-rollback ─────────
            val rollbackPath = tmp.resolve("rollback.sql")
            val reportPath = tmp.resolve("report.json")

            val migrateRunner = SchemaMigrateRunner(
                fileLoader = { _ ->
                    ResolvedSchemaOperand(
                        reference = "desired",
                        schema = desiredSchema,
                        validation = ValidationResult(),
                    )
                },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                rendererFor = { d ->
                    if (d == DatabaseDialect.MYSQL) MysqlDiffDdlGenerator() else null
                },
                executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            )

            val migrateExit = migrateRunner.execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MYSQL,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            migrateExit shouldBe 0
            Files.exists(rollbackPath) shouldBe true
            Files.exists(reportPath) shouldBe true
            val reportText = Files.readString(reportPath)
            reportText shouldContain "status=ok"
            reportText shouldContain "exitCode=0"
            reportText shouldContain "started=true"
            reportText shouldContain "executionError=null"

            // ── 3. Reverse + Compare gegen Ziel-Schema ──────────────────
            MigrationFingerprint.compute(readMysqlSchema(pool)) shouldBe
                MigrationFingerprint.compute(desiredSchema)

            // ── 4. schema rollback --execute --allow-destructive ────────
            val rollbackRunner = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand(pool) },
                executor = { _, statements, _ -> executeAgainstPool(pool, statements) },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            )
            val rollbackExit = rollbackRunner.execute(
                SchemaRollbackRequest(
                    source = rollbackPath,
                    target = "db:placeholder",
                    execute = true,
                    allowDestructive = true,
                ),
            )
            rollbackExit shouldBe 0

            // ── 5. Reverse + Compare gegen Ausgangsschema ───────────────
            MigrationFingerprint.compute(readMysqlSchema(pool)) shouldBe
                MigrationFingerprint.compute(originalSchema)
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
})

private fun execDdl(pool: ConnectionPool, vararg sqls: String) {
    pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            for (sql in sqls) stmt.execute(sql)
        }
    }
}

private fun readMysqlSchema(pool: ConnectionPool): SchemaDefinition =
    MysqlSchemaReader().read(pool, SchemaReadOptions()).schema

private fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-mysql",
    schema = readMysqlSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.MYSQL,
)
