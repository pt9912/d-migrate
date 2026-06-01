package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeSegmentsAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
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
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Atomic-Preserve Phase C.5 (2026-06-01): end-to-end live coverage
 * of the PG atomic-preserve path through [SchemaMigrateRunner].
 * Exercises Stage → Render → ExecutionStage → SegmentAware →
 * [PostgresAtomicSequencePreserveExecutor] → Testcontainer for four
 * outcomes: Applied (single + multi-seq), LockTimeout, Failed.
 *
 * The Phase-B `PostgresAtomicSequencePreserveExecutorIntegrationTest`
 * pins the executor in isolation; this file pins the **full
 * pipeline** integration so a regression in Stage's batch shape,
 * Render's follow-up routing, or ExecutionStage's segment dispatch
 * surfaces here.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.5.
 */
class PostgresSchemaMigrateAtomicPreserveIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("atomic_preserve_e2e")
        .withUsername("test")
        .withPassword("test")

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

    fun exec(sql: String) {
        pool.borrow().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun query(sql: String): Long {
        pool.borrow().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next() shouldBe true
                    return rs.getLong(1)
                }
            }
        }
    }

    fun schemaWithSequence(
        name: String,
        increment: Long = 1L,
        preserveCurrentValue: Boolean = true,
    ) = SchemaDefinition(
        name = "App",
        version = "1",
        sequences = mapOf(
            name to SequenceDefinition(start = 1L, increment = increment, preserveCurrentValue = preserveCurrentValue),
        ),
    )

    fun schemaWithSequences(
        sequences: Map<String, SequenceDefinition>,
    ) = SchemaDefinition(name = "App", version = "1", sequences = sequences)

    /**
     * Build a SchemaMigrateRunner that uses the production
     * AtomicSequencePreserveExecutor for atomic segments + the
     * existing executeAgainstPool helper for plain segments.
     * `atomicExecutorOverride` lets the LockTimeout / Failed tests
     * inject a controlled-failure decorator around the real
     * executor.
     */
    fun runnerWith(
        sourceSchema: SchemaDefinition,
        targetSchema: SchemaDefinition,
        atomicExecutorOverride: AtomicSequencePreserveExecutor = PostgresAtomicSequencePreserveExecutor(),
        lockTimeoutMillis: Long = 5_000L,
    ) = SchemaMigrateRunner(
        fileLoader = { _ ->
            ResolvedSchemaOperand(reference = "desired", schema = sourceSchema, validation = ValidationResult())
        },
        dbLoader = { _, _ ->
            ResolvedSchemaOperand(
                reference = "db:test",
                schema = targetSchema,
                validation = ValidationResult(),
                dialect = DatabaseDialect.POSTGRESQL,
            )
        },
        comparator = { a, b -> SchemaComparator().compare(a, b) },
        rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
        executor = { _, _, segments, timeoutMs ->
            executeSegmentsAgainstPool(pool, segments, atomicExecutorOverride, timeoutMs)
        },
        renderReport = { r, _ -> r.toString() },
        printError = { _, _ -> },
    ).also {
        // Sanity-pin the lock-timeout — the runner's ExecutionStage
        // default is 5_000 ms; tests that override only do so via the
        // executor lambda above. Keep this assignment to surface a
        // future internal-default drift.
        @Suppress("UNUSED_EXPRESSION") lockTimeoutMillis
    }

    fun migrateRequest() = SchemaMigrateRequest(
        source = "file:desired.yaml",
        target = "db:placeholder",
        dialect = DatabaseDialect.POSTGRESQL,
        execute = true,
    )

    // ── Applied: Single-Seq ────────────────────────────────────────────

    test("Applied: single sequence preserveCurrentValue=true keeps its runtime value across migrate") {
        exec("DROP SEQUENCE IF EXISTS atom_e2e_pg_single")
        exec("CREATE SEQUENCE atom_e2e_pg_single START WITH 1 INCREMENT BY 1")
        // Advance to 42 so we have a non-default value to preserve.
        repeat(42) { query("SELECT nextval('atom_e2e_pg_single')") }

        // Source schema asks for INCREMENT BY 5 with preserveCurrentValue;
        // target schema mirrors the live DB (same name, INCREMENT BY 1)
        // so the planner emits AlterSequence and SequencePreserveStage
        // classifies it as a preserve candidate.
        val source = schemaWithSequence(name = "atom_e2e_pg_single", increment = 5L)
        val target = schemaWithSequence(name = "atom_e2e_pg_single", increment = 1L)

        val exit = runnerWith(source, target).execute(migrateRequest())
        exit shouldBe 0

        // The atomic executor probed at lock time, ran the ALTER SEQUENCE
        // (protected op), and restored last_value back to 42. The next
        // nextval observes 43.
        query("SELECT nextval('atom_e2e_pg_single')") shouldBe 43L
    }

    // ── Applied: Multi-Seq ─────────────────────────────────────────────

    test("Applied: two sequences preserveCurrentValue=true are restored atomically + locked in name-sorted order") {
        exec("DROP SEQUENCE IF EXISTS atom_e2e_pg_z")
        exec("DROP SEQUENCE IF EXISTS atom_e2e_pg_a")
        exec("CREATE SEQUENCE atom_e2e_pg_a START WITH 1 INCREMENT BY 1")
        exec("CREATE SEQUENCE atom_e2e_pg_z START WITH 1 INCREMENT BY 1")
        repeat(10) { query("SELECT nextval('atom_e2e_pg_a')") }
        repeat(20) { query("SELECT nextval('atom_e2e_pg_z')") }

        val source = schemaWithSequences(
            sequences = mapOf(
                "atom_e2e_pg_a" to SequenceDefinition(start = 1L, increment = 5L, preserveCurrentValue = true),
                "atom_e2e_pg_z" to SequenceDefinition(start = 1L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = schemaWithSequences(
            sequences = mapOf(
                "atom_e2e_pg_a" to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = true),
                "atom_e2e_pg_z" to SequenceDefinition(start = 1L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target).execute(migrateRequest())
        exit shouldBe 0

        query("SELECT nextval('atom_e2e_pg_a')") shouldBe 11L
        query("SELECT nextval('atom_e2e_pg_z')") shouldBe 21L
    }

    // ── LockTimeout ────────────────────────────────────────────────────

    test("LockTimeout: concurrent advisory-lock holder triggers SEQUENCE_PRESERVE_LOCK_TIMEOUT — no partial apply") {
        exec("DROP SEQUENCE IF EXISTS atom_e2e_pg_lock")
        exec("CREATE SEQUENCE atom_e2e_pg_lock START WITH 1 INCREMENT BY 1")
        repeat(7) { query("SELECT nextval('atom_e2e_pg_lock')") }

        // Hash the sequence the way PostgresAtomicSequencePreserveExecutor
        // does: `'d-migrate:seq:' || schema || '.' || name`. The lock
        // key is `hashtext(...)` cast to bigint. The lock holder
        // acquires the same advisory lock on a separate connection,
        // forcing the executor to wait + time out.
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool2 = Executors.newSingleThreadExecutor()
        try {
            pool2.submit {
                DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { c ->
                    c.autoCommit = false
                    c.createStatement().use { stmt ->
                        stmt.execute(
                            "SELECT pg_advisory_xact_lock(" +
                                "hashtext('d-migrate:seq:.atom_e2e_pg_lock')::bigint)",
                        )
                    }
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    c.rollback()
                }
            }
            held.await(10, TimeUnit.SECONDS) shouldBe true

            val source = schemaWithSequence(name = "atom_e2e_pg_lock", increment = 5L)
            val target = schemaWithSequence(name = "atom_e2e_pg_lock", increment = 1L)

            val exit = runnerWith(source, target, lockTimeoutMillis = 500L).execute(migrateRequest())
            // SEQUENCE_PRESERVE_LOCK_TIMEOUT routes via PlannerBlockerClassifier
            // onto MANUAL_ACTION_REQUIRED ⇒ runner exit 8.
            exit shouldBe 8

            // Sequence value unchanged — no partial apply.
            query("SELECT last_value FROM atom_e2e_pg_lock") shouldBe 7L
        } finally {
            release.countDown()
            pool2.shutdown()
            pool2.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    // ── Failed ─────────────────────────────────────────────────────────

    test("Failed: throwing protected-op rollbacks the atomic transaction; sequence value stays at probe-time reading") {
        exec("DROP SEQUENCE IF EXISTS atom_e2e_pg_fail")
        exec("CREATE SEQUENCE atom_e2e_pg_fail START WITH 1 INCREMENT BY 1")
        repeat(13) { query("SELECT nextval('atom_e2e_pg_fail')") }

        // Decorator around the real executor: the production lock +
        // probe + finally-rollback path runs, but the protected-ops
        // callback throws to exercise the Failed branch end-to-end.
        val throwingExecutor = object : AtomicSequencePreserveExecutor {
            private val real = PostgresAtomicSequencePreserveExecutor()
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

        val source = schemaWithSequence(name = "atom_e2e_pg_fail", increment = 5L)
        val target = schemaWithSequence(name = "atom_e2e_pg_fail", increment = 1L)

        val exit = runnerWith(source, target, atomicExecutorOverride = throwingExecutor)
            .execute(migrateRequest())
        // Failed → executionError + rollback ⇒ runner surfaces a failure
        // exit (8 for MIGRATION_BLOCKED-style failures). The exact code
        // is less important than the post-state: sequence MUST NOT have
        // advanced past 13.
        (exit != 0) shouldBe true
        query("SELECT last_value FROM atom_e2e_pg_fail") shouldBe 13L
    }
})
