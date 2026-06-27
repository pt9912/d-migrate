package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.connection.DatabaseConnection

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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists

/**
 * Atomic-Preserve Phase C.5 (2026-06-01): end-to-end live coverage
 * of the SQLite atomic-preserve path through [SchemaMigrateRunner].
 *
 * SQLite uses the `dmg_sequences` helper-table emulation gated by
 * `--sqlite-named-sequences helper_table`; tests bootstrap the
 * helper table directly + opt into helper-table mode on the
 * migrate request.
 *
 * File-backed (not `:memory:`) so the LockTimeout test can open a
 * second raw connection that contends for the RESERVED lock.
 */
class SqliteSchemaMigrateAtomicPreserveIntegrationTest : FunSpec({

    lateinit var dbDir: Path
    lateinit var dbPath: Path
    lateinit var pool: ConnectionPool

    beforeSpec {
        dbDir = createTempDirectory("dmigrate-sqlite-c5")
        dbPath = dbDir.resolve("preserve.db")
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null,
                port = null,
                database = dbPath.absolutePathString(),
                user = null,
                password = null,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        runCatching { dbPath.deleteIfExists() }
        runCatching { Files.deleteIfExists(dbDir) }
    }

    fun bootstrap(seedRows: Map<String, Long>) {
        // Canonical SQLite `dmg_sequences` schema mirrors
        // SqliteSequenceEmulationTemplates.supportTableSql (in
        // :adapters:driven:driver-sqlite, internal). The full column
        // set is required because the diff renderer emits
        // `UPDATE … SET "increment_by" = …` etc. — a stripped-down
        // schema would surface as `SQLITE_ERROR: no such column`.
        pool.borrow().asJdbc().use { c ->
            c.autoCommit = true
            c.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS \"dmg_sequences\"")
                stmt.execute(
                    """
                    CREATE TABLE "dmg_sequences" (
                        "managed_by" TEXT NOT NULL,
                        "format_version" TEXT NOT NULL,
                        "name" TEXT NOT NULL,
                        "next_value" INTEGER NOT NULL,
                        "last_returned_value" INTEGER NULL,
                        "exhausted" INTEGER NOT NULL DEFAULT 0,
                        "increment_by" INTEGER NOT NULL,
                        "min_value" INTEGER NULL,
                        "max_value" INTEGER NULL,
                        "cycle_enabled" INTEGER NOT NULL,
                        "cache_size" INTEGER NULL,
                        PRIMARY KEY ("name")
                    )
                    """.trimIndent(),
                )
                for ((name, value) in seedRows) {
                    stmt.execute(
                        """
                        INSERT INTO "dmg_sequences" (
                            "managed_by", "format_version", "name", "next_value",
                            "last_returned_value", "exhausted", "increment_by",
                            "min_value", "max_value", "cycle_enabled", "cache_size"
                        ) VALUES ('d-migrate', 'sqlite-sequence-v1', '$name', $value,
                            NULL, 0, 1, NULL, NULL, 0, NULL)
                        """.trimIndent(),
                    )
                }
            }
        }
    }

    fun nextValueOf(name: String): Long {
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT \"next_value\" FROM \"dmg_sequences\" WHERE \"name\" = '$name'").use { rs ->
                    rs.next() shouldBe true
                    return rs.getLong(1)
                }
            }
        }
    }

    fun rawConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.absolutePathString()}")

    /**
     * Atomic-Preserve follow-up (Finding #6, 2026-06-01; revised by
     * Service-Mode Sub-Slice A, 2026-06-02): decorator that runs
     * the real SQLite executor against a fresh DriverManager
     * connection (xerial-sqlite Hikari-pool quirk: the pooled
     * `BEGIN IMMEDIATE` does not observe a holder's RESERVED lock
     * acquired via raw DriverManager — Phase B's standalone IT
     * works because it uses TWO DriverManager connections without
     * a pool).
     *
     * Sub-Slice A removed the previous timeout-override responsibility
     * from this factory; the lock-timeout budget now flows via
     * `SchemaMigrateRequest.lockTimeoutMillis` → ExecutionStage →
     * executor lambda → the `lockTimeoutMillis` parameter passed in
     * here. The decorator only relays it untouched.
     */
    fun freshConnExecutor(): AtomicSequencePreserveExecutor = object : AtomicSequencePreserveExecutor {
        private val real = SqliteAtomicSequencePreserveExecutor()
        override fun execute(
            connection: DatabaseConnection,
            batch: AtomicSequencePreserveBatch,
            lockTimeoutMillis: Long,
            cancellationToken: dev.dmigrate.core.cancel.CancellationToken,
            executeProtectedOperations: (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
        ): AtomicSequencePreserveResult =
            rawConnection().use { freshConn ->
                freshConn.autoCommit = true
                real.execute(connection = JdbcDatabaseConnection(freshConn), batch = batch,
                lockTimeoutMillis = lockTimeoutMillis,
                cancellationToken = cancellationToken,
                executeProtectedOperations = executeProtectedOperations,)
            }
    }

    fun runnerWith(
        sourceSchema: SchemaDefinition,
        targetSchema: SchemaDefinition,
        atomicExecutorOverride: AtomicSequencePreserveExecutor = SqliteAtomicSequencePreserveExecutor(),
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
                    dialect = DatabaseDialect.SQLITE,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = { d -> if (d == DatabaseDialect.SQLITE) SqliteDiffDdlGenerator() else null },
            executor = { _, _, segments, lockTimeoutMs, _ ->
                executeSegmentsAgainstPool(pool, segments, atomicExecutorOverride, lockTimeoutMs)
            },
            renderReport = { r, _ -> r.toString() },
            printError = { _, _ -> },
        )
    }

    fun migrateRequest(lockTimeoutMillis: Long? = null) = SchemaMigrateRequest(
        // SchemaMigratePreparation.validateRequest enforces `--execute
        // requires --report`. The runner's fileLoader / dbLoader are
        // stubbed above, so the actual file/db sources are never read.
        source = "file:${dbDir.resolve("desired.yaml")}",
        target = "db:placeholder",
        dialect = DatabaseDialect.SQLITE,
        execute = true,
        sqliteNamedSequences = "helper_table",
        report = dbDir.resolve("report.json"),
        lockTimeoutMillis = lockTimeoutMillis,
    )

    // ── Applied: Single-Seq ────────────────────────────────────────────

    test("Applied: single sequence preserveCurrentValue=true keeps next_value across migrate") {
        bootstrap(mapOf("e2e_sqlite_one" to 142L))

        val source = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_sqlite_one" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_sqlite_one" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target).execute(migrateRequest())
        exit shouldBe 0
        nextValueOf("e2e_sqlite_one") shouldBe 142L
    }

    // ── Applied: Multi-Seq ─────────────────────────────────────────────

    test("Applied: two sequences preserveCurrentValue=true are restored atomically (name-sorted lock order)") {
        bootstrap(mapOf("e2e_sqlite_a" to 110L, "e2e_sqlite_z" to 220L))

        val source = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_sqlite_a" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
                "e2e_sqlite_z" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_sqlite_a" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
                "e2e_sqlite_z" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target).execute(migrateRequest())
        exit shouldBe 0
        nextValueOf("e2e_sqlite_a") shouldBe 110L
        nextValueOf("e2e_sqlite_z") shouldBe 220L
    }

    // ── LockTimeout ────────────────────────────────────────────────────

    test("LockTimeout: concurrent BEGIN IMMEDIATE holder triggers SEQUENCE_PRESERVE_LOCK_TIMEOUT") {
        bootstrap(mapOf("e2e_sqlite_lock" to 175L))

        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = Executors.newSingleThreadExecutor()
        try {
            holder.submit {
                // Phase B SqliteAtomicSequencePreserveExecutorIntegrationTest
                // pattern: open the connection in the worker thread,
                // set autocommit=true, run BEGIN IMMEDIATE on its own
                // Statement, then signal "lock held". BEGIN IMMEDIATE
                // alone takes the RESERVED lock; we don't need an
                // UPDATE on top. xerial's setAutoCommit is idempotent
                // (same-value = no-op, see
                // org.sqlite.SQLiteConnection.setAutoCommit) and raw
                // SQL via Statement.execute is not auto-committed on
                // Statement.close — the explicit transaction persists
                // until the matching ROLLBACK below.
                rawConnection().use { c ->
                    c.autoCommit = true
                    c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                    held.countDown()
                    release.await(30, TimeUnit.SECONDS)
                    c.createStatement().use { it.execute("ROLLBACK") }
                }
            }
            held.await(10, TimeUnit.SECONDS) shouldBe true

            val source = SchemaDefinition(
                name = "App", version = "1",
                sequences = mapOf(
                    "e2e_sqlite_lock" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
                ),
            )
            val target = SchemaDefinition(
                name = "App", version = "1",
                sequences = mapOf(
                    "e2e_sqlite_lock" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
                ),
            )
            // SQLite-specific lock-contention quirk: when the atomic
            // executor borrows from the Hikari pool (URL param
            // `journal_mode=wal`) the BEGIN IMMEDIATE somehow does not
            // observe the holder's RESERVED lock acquired via raw
            // DriverManager. Phase B's IT
            // (`SqliteAtomicSequencePreserveExecutorIntegrationTest`)
            // works because it uses TWO DriverManager connections (no
            // pool). Mirror that: open a fresh DriverManager connection
            // for the atomic transaction so the test sees the same
            // contention path Phase B exercises.
            // Atomic-Preserve Service-Mode Sub-Slice A (2026-06-02):
            // the lock-timeout budget flows via
            // `SchemaMigrateRequest.lockTimeoutMillis`. The
            // freshConnExecutor decorator stays for the xerial-sqlite
            // Hikari-pool quirk (documented above) but no longer
            // overrides the timeout.
            val exit = runnerWith(source, target, atomicExecutorOverride = freshConnExecutor())
                .execute(migrateRequest(lockTimeoutMillis = 500L))
            // LockTimeout maps onto ExecutionTrace.executionError →
            // runner exits 5; the post-condition that matters is
            // "no partial apply".
            (exit != 0) shouldBe true
            nextValueOf("e2e_sqlite_lock") shouldBe 175L
        } finally {
            release.countDown()
            holder.shutdown()
            holder.awaitTermination(30, TimeUnit.SECONDS)
        }
    }

    // ── Failed ─────────────────────────────────────────────────────────

    test("Failed: throwing protected-op rollbacks the atomic transaction; next_value stays at probe-time reading") {
        bootstrap(mapOf("e2e_sqlite_fail" to 188L))

        val throwingExecutor = object : AtomicSequencePreserveExecutor {
            private val real = SqliteAtomicSequencePreserveExecutor()
            override fun execute(
                connection: DatabaseConnection,
                batch: AtomicSequencePreserveBatch,
                lockTimeoutMillis: Long,
                cancellationToken: dev.dmigrate.core.cancel.CancellationToken,
                executeProtectedOperations: (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
            ): AtomicSequencePreserveResult = real.execute(
                connection = connection,
                batch = batch,
                lockTimeoutMillis = lockTimeoutMillis,
                cancellationToken = cancellationToken,
                executeProtectedOperations = { _, _ ->
                    throw RuntimeException("simulated DDL failure inside protected ops")
                },
            )
        }

        val source = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_sqlite_fail" to SequenceDefinition(start = 100L, increment = 5L, preserveCurrentValue = true),
            ),
        )
        val target = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf(
                "e2e_sqlite_fail" to SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true),
            ),
        )

        val exit = runnerWith(source, target, atomicExecutorOverride = throwingExecutor).execute(migrateRequest())
        (exit != 0) shouldBe true
        nextValueOf("e2e_sqlite_fail") shouldBe 188L
    }
})
