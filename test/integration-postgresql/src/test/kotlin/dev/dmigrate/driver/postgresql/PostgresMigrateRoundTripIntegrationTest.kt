package dev.dmigrate.driver.postgresql

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
import org.testcontainers.postgresql.PostgreSQLContainer
import java.nio.file.Files
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

private val IntegrationTag = NamedTag("integration")

/**
 * F.2 — PostgreSQL round-trip smoke (`docs/planning/in-progress/diffresult-migration-plan.md §F.2`).
 *
 * Drives `SchemaMigrateRunner` and `SchemaRollbackRunner` end-to-end
 * against a live PostgreSQL via Testcontainers, exercising the pipeline
 * the CLI also wires up:
 *
 * 1. set up Ausgangsschema in the DB
 * 2. `schema migrate --execute --generate-rollback --rollback-output …`
 *    (the runner's own post-compare must succeed → exit 0, artefact
 *    written)
 * 3. **independent** reverse + content-fingerprint compare against the
 *    desired Soll-schema — verifies the runner's post-compare from the
 *    test's vantage point
 * 4. `schema rollback --execute --allow-destructive` against the same
 *    DB using the artefact from step 2
 * 5. **independent** reverse + content-fingerprint compare against the
 *    Ausgangsschema
 *
 * The Soll/Ist comparison uses [MigrationFingerprint.compute], which is
 * a content-only hash (no `name`/`version` projection) — see the
 * fingerprint docstring. That makes the smoke immune to the test-side
 * label choice and consistent with how the rollback runner verifies
 * `TARGET_STATE_MISMATCH`.
 */
class PostgresMigrateRoundTripIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var config: ConnectionConfig
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        pool = HikariConnectionPoolFactory.create(config)
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("AddColumn round-trip leaves Ist == Ausgangsschema (content fingerprints match)") {
        val tmp = createTempDirectory("pg-roundtrip-f2")
        try {
            // ── 1. Ausgangsschema in der DB einrichten ──────────────────
            execDdl(
                pool,
                "DROP TABLE IF EXISTS round_trip",
                "CREATE TABLE round_trip (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)",
            )

            val originalSchema = SchemaDefinition(
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
            val desiredSchema = SchemaDefinition(
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

            // Sanity: Ausgangs-DB matches our Ausgangsschema content.
            MigrationFingerprint.compute(readPgSchema(pool)) shouldBe
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
                    if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null
                },
                executor = { _, statements, _ -> executeOnPg(pool, statements) },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> System.err.println("[$src] $msg") },
            )

            val migrateExit = migrateRunner.execute(
                SchemaMigrateRequest(
                    // CompareOperandParser requires a `file:` prefix; the path itself is
                    // ignored because our fileLoader returns the in-code desired schema.
                    source = "file:${tmp.resolve("ignored-desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL,
                    report = reportPath,
                    rollbackOutput = rollbackPath,
                    generateRollback = true,
                    execute = true,
                ),
            )
            migrateExit shouldBe 0
            Files.exists(rollbackPath) shouldBe true
            Files.exists(reportPath) shouldBe true

            // ── 3. Reverse + Compare gegen Ziel-Schema ──────────────────
            MigrationFingerprint.compute(readPgSchema(pool)) shouldBe
                MigrationFingerprint.compute(desiredSchema)

            // ── 4. schema rollback --execute --allow-destructive ────────
            val rollbackRunner = SchemaRollbackRunner(
                dbLoader = { _, _ -> liveOperand(pool) },
                executor = { _, statements, _ -> executeOnPg(pool, statements) },
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
            MigrationFingerprint.compute(readPgSchema(pool)) shouldBe
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

private fun readPgSchema(pool: ConnectionPool): SchemaDefinition =
    PostgresSchemaReader().read(pool, SchemaReadOptions()).schema

private fun liveOperand(pool: ConnectionPool): ResolvedSchemaOperand = ResolvedSchemaOperand(
    reference = "live-pg",
    schema = readPgSchema(pool),
    validation = ValidationResult(),
    dialect = DatabaseDialect.POSTGRESQL,
)

/**
 * Mirrors `JdbcMigrationExecutor` (CLI module) but reuses the test
 * pool: autocommit off, single statement-per-execute, rollback on
 * failure with `transactionRolledBack` / `sideEffectsPossible`
 * tracking.
 */
@Suppress("ReturnCount")
private fun executeOnPg(
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
