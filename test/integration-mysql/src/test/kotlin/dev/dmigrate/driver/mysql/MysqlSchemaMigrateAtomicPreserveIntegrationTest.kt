package dev.dmigrate.driver.mysql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeSegmentsAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * Atomic-Preserve Phase C.5 (2026-06-01): end-to-end live coverage
 * of the MySQL atomic-preserve path through [SchemaMigrateRunner].
 * Companion to [PostgresSchemaMigrateAtomicPreserveIntegrationTest]
 * and [SqliteSchemaMigrateAtomicPreserveIntegrationTest].
 *
 * MySQL uses the `dmg_sequences` helper-table emulation; tests
 * bootstrap the helper schema via [MysqlDdlGenerator] and then drive
 * SchemaMigrateRunner across an [AlterSequence] / preserve flow.
 */
class MysqlSchemaMigrateAtomicPreserveIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:8")
        .withDatabaseName("atomic_preserve_e2e")
        .withUsername("test")
        .withPassword("test")
        .withCommand("--log-bin-trust-function-creators=1")

    lateinit var config: ConnectionConfig
    lateinit var pool: ConnectionPool
    lateinit var tmpDir: Path

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
        pool = HikariConnectionPoolFactory.create(config)
        tmpDir = createTempDirectory("dmigrate-mysql-c5-")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
        runCatching { Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }

    fun exec(sql: String) {
        pool.borrow().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun nextValueOf(name: String): Long {
        pool.borrow().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT `next_value` FROM `dmg_sequences` WHERE `name` = '$name'").use { rs ->
                    rs.next() shouldBe true
                    return rs.getLong(1)
                }
            }
        }
    }

    fun bootstrap(sequences: Map<String, SequenceDefinition>) {
        exec("DROP FUNCTION IF EXISTS `dmg_nextval`")
        exec("DROP FUNCTION IF EXISTS `dmg_setval`")
        exec("DROP TABLE IF EXISTS `dmg_sequences`")
        val schema = SchemaDefinition(name = "AtomicE2E", version = "1", sequences = sequences)
        val result = MysqlDdlGenerator().generate(
            schema,
            DdlGenerationOptions(
                dialectContext = DdlDialectContext.MySql(namedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE),
            ),
        )
        for (block in splitMysqlStatements(result.render())) {
            if (block.isNotBlank()) exec(block)
        }
    }

    fun runnerWith(
        sourceSchema: SchemaDefinition,
        targetSchema: SchemaDefinition,
        atomicExecutorOverride: AtomicSequencePreserveExecutor = MysqlAtomicSequencePreserveExecutor(),
    ): SchemaMigrateRunner {
        var dbLoadCalls = 0
        return SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "desired", schema = sourceSchema, validation = ValidationResult())
            },
            dbLoader = { _, _ ->
                val schema = if (dbLoadCalls++ == 0) targetSchema else sourceSchema
                ResolvedSchemaOperand(
                    reference = "db:test",
                    schema = schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.MYSQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { d -> if (d == DatabaseDialect.MYSQL) MysqlDiffDdlGenerator() else null },
            executor = { _, _, segments, lockTimeoutMs ->
                executeSegmentsAgainstPool(pool, segments, atomicExecutorOverride, lockTimeoutMs)
            },
            renderReport = { r, _ -> r.toString() },
            printError = { _, _ -> },
        )
    }

    fun migrateRequest() = SchemaMigrateRequest(
        // SchemaMigratePreparation.validateRequest enforces `--execute
        // requires --report`. The runner's fileLoader / dbLoader are
        // stubbed above, so the actual file/db sources are never read.
        source = "file:${tmpDir.resolve("desired.yaml")}",
        target = "db:placeholder",
        dialect = DatabaseDialect.MYSQL,
        execute = true,
        report = tmpDir.resolve("report.json"),
        // MysqlDiffSequenceOps.ensureHelperMode blocks the render
        // unless this is set. Mirror of sqliteNamedSequences for PG/
        // SQLite ITs; the executor lambda above bootstraps the
        // dmg_sequences helper table separately.
        mysqlNamedSequences = "helper_table",
    )

    // ── Applied: Single-Seq ────────────────────────────────────────────

    test("Applied: single sequence preserveCurrentValue=true keeps next_value across migrate") {
        bootstrap(mapOf("e2e_mysql_one" to SequenceDefinition(start = 100L, increment = 1L)))
        exec("UPDATE `dmg_sequences` SET `next_value` = 142 WHERE `name` = 'e2e_mysql_one'")

        val source = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_mysql_one" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_mysql_one" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target).execute(migrateRequest())
        exit shouldBe 0
        nextValueOf("e2e_mysql_one") shouldBe 142L
    }

    // ── Applied: Multi-Seq ─────────────────────────────────────────────

    test("Applied: two sequences preserveCurrentValue=true are restored atomically (name-sorted lock order)") {
        bootstrap(
            mapOf(
                "e2e_mysql_a" to SequenceDefinition(start = 100L, increment = 1L),
                "e2e_mysql_z" to SequenceDefinition(start = 100L, increment = 1L),
            ),
        )
        exec("UPDATE `dmg_sequences` SET `next_value` = 110 WHERE `name` = 'e2e_mysql_a'")
        exec("UPDATE `dmg_sequences` SET `next_value` = 220 WHERE `name` = 'e2e_mysql_z'")

        val source = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_mysql_a" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
                "e2e_mysql_z" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_mysql_a" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
                "e2e_mysql_z" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target).execute(migrateRequest())
        exit shouldBe 0
        nextValueOf("e2e_mysql_a") shouldBe 110L
        nextValueOf("e2e_mysql_z") shouldBe 220L
    }

    // ── LockTimeout ────────────────────────────────────────────────────

    test("LockTimeout: concurrent SELECT … FOR UPDATE holder triggers SEQUENCE_PRESERVE_LOCK_TIMEOUT") {
        bootstrap(mapOf("e2e_mysql_lock" to SequenceDefinition(start = 100L, increment = 1L)))
        exec("UPDATE `dmg_sequences` SET `next_value` = 175 WHERE `name` = 'e2e_mysql_lock'")

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        try {
            holder.submit {
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                    c.autoCommit = false
                    c.createStatement().use { s ->
                        s.executeQuery(
                            "SELECT `next_value` FROM `dmg_sequences` " +
                                "WHERE `name` = 'e2e_mysql_lock' FOR UPDATE",
                        ).use { /* consume */ }
                    }
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    c.rollback()
                }
            }
            held.await(10, TimeUnit.SECONDS) shouldBe true

            val source = SchemaDefinition(
                name = "App", version = "1",
                sequences = mapOf(
                    "e2e_mysql_lock" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
                ),
            )
            val target = SchemaDefinition(
                name = "App", version = "1",
                sequences = mapOf(
                    "e2e_mysql_lock" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
                ),
            )
            // Use the executor directly with a tight lockTimeoutMillis via
            // a wrapper that pins it. The lock timeout flows through
            // SchemaMigrateExecutionStage's default (5s); we want a
            // faster timeout. Wrap the real executor in a decorator
            // that forces the timeout to 1s.
            val tightTimeoutExecutor = object : AtomicSequencePreserveExecutor {
                private val real = MysqlAtomicSequencePreserveExecutor()
                override fun execute(
                    connection: Connection,
                    batch: AtomicSequencePreserveBatch,
                    lockTimeoutMillis: Long,
                    executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
                ): AtomicSequencePreserveResult = real.execute(
                    connection = connection,
                    batch = batch,
                    // override to 1s
                    lockTimeoutMillis = 1_000L,
                    executeProtectedOperations = executeProtectedOperations,
                )
            }
            val exit = runnerWith(source, target, atomicExecutorOverride = tightTimeoutExecutor)
                .execute(migrateRequest())
            // LockTimeout maps onto ExecutionTrace.executionError →
            // runner exits 5; the post-condition that matters is
            // "no partial apply".
            (exit != 0) shouldBe true
            nextValueOf("e2e_mysql_lock") shouldBe 175L
        } finally {
            release.countDown()
            holder.shutdown()
            holder.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    // ── Failed ─────────────────────────────────────────────────────────

    test("Failed: throwing protected-op rollbacks the atomic transaction; next_value stays at probe-time reading") {
        bootstrap(mapOf("e2e_mysql_fail" to SequenceDefinition(start = 100L, increment = 1L)))
        exec("UPDATE `dmg_sequences` SET `next_value` = 188 WHERE `name` = 'e2e_mysql_fail'")

        val throwingExecutor = object : AtomicSequencePreserveExecutor {
            private val real = MysqlAtomicSequencePreserveExecutor()
            override fun execute(
                connection: Connection,
                batch: AtomicSequencePreserveBatch,
                lockTimeoutMillis: Long,
                executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
            ): AtomicSequencePreserveResult = real.execute(
                connection = connection,
                batch = batch,
                lockTimeoutMillis = lockTimeoutMillis,
                executeProtectedOperations = { _, _ ->
                    throw RuntimeException("simulated DDL failure inside protected ops")
                },
            )
        }

        val source = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_mysql_fail" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_mysql_fail" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target, atomicExecutorOverride = throwingExecutor).execute(migrateRequest())
        (exit != 0) shouldBe true
        nextValueOf("e2e_mysql_fail") shouldBe 188L
    }
})
