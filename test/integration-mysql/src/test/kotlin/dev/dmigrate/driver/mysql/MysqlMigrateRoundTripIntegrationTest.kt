package dev.dmigrate.driver.mysql

import dev.dmigrate.cli.commands.ExecutionTrace
import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.SchemaRollbackRequest
import dev.dmigrate.cli.commands.SchemaRollbackRunner
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.model.ColumnDefinition
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
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

private val IntegrationTag = NamedTag("integration")

/**
 * F.3 — MySQL round-trip smoke (`docs/planning/in-progress/diffresult-migration-plan.md §F.3`).
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
 * - `AlterColumnNullability` stays carve-out per §F.3; this smoke
 *   never exercises it (only AddColumn↔DropColumn).
 *
 * Steps mirror F.2 §1-5: set up Ausgangs-DB, run
 * `schema migrate --execute --generate-rollback`, independent
 * reverse+fingerprint compare against Soll, run
 * `schema rollback --execute --allow-destructive`, independent
 * reverse+fingerprint compare against Ausgangsschema.
 */
class MysqlMigrateRoundTripIntegrationTest : FunSpec({

    tags(IntegrationTag)

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
                executor = { _, statements, _ -> executeOnMysql(pool, statements) },
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
                executor = { _, statements, _ -> executeOnMysql(pool, statements) },
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
    pool.borrow().use { conn ->
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

/**
 * Mirrors `JdbcMigrationExecutor` (CLI module, `internal`) but reuses
 * the test pool: autocommit off, single statement-per-execute,
 * rollback on failure with `transactionRolledBack` /
 * `sideEffectsPossible` tracking. MySQL 8.0 honours explicit
 * transaction boundaries for DDL when `autoCommit = false` is set
 * upfront; no SAVEPOINT needed.
 */
@Suppress("ReturnCount")
private fun executeOnMysql(
    pool: ConnectionPool,
    statements: List<MigrationDdlStatement>,
): ExecutionTrace {
    if (statements.isEmpty()) {
        return ExecutionTrace(executionStarted = true, executionCompleted = true)
    }
    return pool.borrow().use { conn ->
        conn.autoCommit = false
        var attempted = 0
        var lastIds: Set<String> = emptySet()
        try {
            conn.createStatement().use { jdbcStmt ->
                for (s in statements) {
                    lastIds = s.operationIds
                    attempted++
                    jdbcStmt.execute(s.sql)
                }
            }
            conn.commit()
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
            )
        } catch (e: SQLException) {
            val (rolledBack, sideEffects) = try {
                conn.rollback()
                true to false
            } catch (_: SQLException) {
                false to true
            }
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = attempted,
                lastStatementOperationIds = lastIds,
                transactionRolledBack = rolledBack,
                sideEffectsPossible = sideEffects,
                executionError = e.message ?: e::class.simpleName,
            )
        }
    }
}
