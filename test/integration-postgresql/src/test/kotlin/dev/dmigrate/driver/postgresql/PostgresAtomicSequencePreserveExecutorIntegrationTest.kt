package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Atomic-Preserve Phase B.2 (2026-05-31): live-PostgreSQL coverage
 * for [PostgresAtomicSequencePreserveExecutor]. Pins both the
 * happy-path (single-seq + multi-seq batch) and the lock-timeout
 * race against a concurrent `nextval` holder.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase B DoD ("Executor-Tests mit echten Live-Containern
 * (Lock-Race-Reproduktion) für Single-Seq und Multi-Seq Batch inkl.
 * Timeout-Leckageprüfung").
 */
class PostgresAtomicSequencePreserveExecutorIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("atomic_preserve_it")
        .withUsername("test")
        .withPassword("test")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun conn() = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun exec(sql: String) {
        conn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun query(sql: String): Long {
        conn().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs ->
                    rs.next() shouldBe true
                    return rs.getLong(1)
                }
            }
        }
    }

    fun pgRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.POSTGRESQL)

    val executor = PostgresAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    test("Applied: single-sequence batch commits the probe + restore atomically") {
        exec("DROP SEQUENCE IF EXISTS atom_seq_a")
        exec("CREATE SEQUENCE atom_seq_a START WITH 1")
        // Advance the sequence so probe sees a meaningful value.
        query("SELECT nextval('atom_seq_a')") shouldBe 1L
        query("SELECT nextval('atom_seq_a')") shouldBe 2L

        val ref = pgRef("atom_seq_a")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(
                    sequenceRef = ref,
                    renderRestore = { probe ->
                        listOf("SELECT setval('atom_seq_a', ${probe.value}, ${probe.isCalled})")
                    },
                ),
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = listOf("op-atom_seq_a"),
        )

        conn().use { c ->
            val result = executor.execute(c, batch, lockTimeoutMillis = 5_000) { protectedConn, ops ->
                ops shouldBe listOf(protectedOpId)
                // Protected op: bump the sequence further so the
                // *restore* statement undoes the advance and pins
                // the probed value back. After commit the next
                // `nextval` returns `probedValue + increment`.
                protectedConn.createStatement().use { s ->
                    s.execute("SELECT nextval('atom_seq_a')")
                }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 1)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            result.refs shouldBe listOf(ref)
        }

        // After the executor commits, the next nextval reflects the
        // restored last_value (2) plus increment, i.e. 3 — not 4
        // (which is what the protected op alone would have left).
        query("SELECT nextval('atom_seq_a')") shouldBe 3L
    }

    test("Applied: multi-sequence batch locks in deterministic name-sorted order") {
        exec("DROP SEQUENCE IF EXISTS atom_seq_z")
        exec("DROP SEQUENCE IF EXISTS atom_seq_a_multi")
        exec("CREATE SEQUENCE atom_seq_a_multi START WITH 10")
        exec("CREATE SEQUENCE atom_seq_z START WITH 100")
        query("SELECT nextval('atom_seq_a_multi')")
        query("SELECT nextval('atom_seq_z')")

        // Caller supplies refs in REVERSE alphabetical order; the
        // executor must still acquire locks alphabetically (plan
        // §2 (3) deadlock-diamond avoidance).
        val refA = pgRef("atom_seq_a_multi")
        val refZ = pgRef("atom_seq_z")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(refZ) { probe ->
                    listOf("SELECT setval('atom_seq_z', ${probe.value}, ${probe.isCalled})")
                },
                AtomicSequencePreserveRequest(refA) { probe ->
                    listOf("SELECT setval('atom_seq_a_multi', ${probe.value}, ${probe.isCalled})")
                },
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = listOf("op-multi"),
        )

        conn().use { c ->
            val result = executor.execute(c, batch, lockTimeoutMillis = 5_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            // Refs are reported in commit (sorted) order.
            result.refs shouldBe listOf(refA, refZ)
        }
    }

    test("NotFound: missing sequence rolls back the batch") {
        // Make sure the sequence does NOT exist in the target DB.
        exec("DROP SEQUENCE IF EXISTS atom_seq_missing")
        val ref = pgRef("atom_seq_missing")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf("SELECT setval('atom_seq_missing', ${probe.value})")
                },
            ),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        conn().use { c ->
            val result = executor.execute(c, batch, lockTimeoutMillis = 1_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
            result.refs shouldBe listOf(ref)
        }
    }

    test("LockTimeout: concurrent advisory-lock holder triggers SQLSTATE 55P03") {
        // Plan-Doc §4.1 (corrected): PG sequences are lock-free,
        // so the executor uses `pg_advisory_xact_lock(hashtext(
        // "d-migrate:seq:.atom_seq_locked")::bigint)`. The holder
        // grabs the same advisory key on another connection; the
        // executor's acquisition then times out with SQLSTATE 55P03
        // and surfaces LockTimeout.
        exec("DROP SEQUENCE IF EXISTS atom_seq_locked")
        exec("CREATE SEQUENCE atom_seq_locked START WITH 1")
        query("SELECT nextval('atom_seq_locked')")

        val holderAcquired = CountDownLatch(1)
        val holderRelease = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Unit> {
                DriverManager.getConnection(
                    container.jdbcUrl, container.username, container.password,
                ).use { holderConn ->
                    holderConn.autoCommit = false
                    holderConn.prepareStatement(
                        "SELECT pg_advisory_xact_lock(hashtext(?)::bigint)",
                    ).use { ps ->
                        ps.setString(1, "d-migrate:seq:.atom_seq_locked")
                        ps.executeQuery().use { rs -> rs.next() }
                    }
                    holderAcquired.countDown()
                    holderRelease.await(20, TimeUnit.SECONDS)
                    holderConn.rollback()
                }
            }
            holderAcquired.await(10, TimeUnit.SECONDS) shouldBe true

            val ref = pgRef("atom_seq_locked")
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { probe ->
                        listOf("SELECT setval('atom_seq_locked', ${probe.value})")
                    },
                ),
                protectedOperationIds = emptyList(),
                internalFollowUpIds = emptyList(),
            )
            conn().use { c ->
                val result = executor.execute(c, batch, lockTimeoutMillis = 500) { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(0)
                }
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
                result.refs shouldBe listOf(ref)
            }

            holderRelease.countDown()
            future.get(10, TimeUnit.SECONDS)
        } finally {
            holderRelease.countDown()
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    test("autocommit is restored to its borrow-time value after execute() returns") {
        exec("DROP SEQUENCE IF EXISTS atom_seq_autocommit")
        exec("CREATE SEQUENCE atom_seq_autocommit START WITH 1")
        query("SELECT nextval('atom_seq_autocommit')")

        val ref = pgRef("atom_seq_autocommit")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf("SELECT setval('atom_seq_autocommit', ${probe.value}, ${probe.isCalled})")
                },
            ),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        conn().use { c ->
            c.autoCommit shouldBe true
            val result = executor.execute(c, batch, lockTimeoutMillis = 5_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            // Verify the executor restored autocommit — otherwise
            // the pooled connection would leak transactional mode
            // into the next borrow and break unrelated queries.
            c.autoCommit shouldBe true
        }
    }

    // ─── Service-Mode Sub-Slice E: Cancellation ─────────────────────

    test("Cancelled: pre-BEGIN cancel short-circuits without opening a transaction") {
        exec("DROP SEQUENCE IF EXISTS atom_seq_cancel_pre")
        exec("CREATE SEQUENCE atom_seq_cancel_pre START WITH 1")
        // Advance so probe would see a non-trivial last_value.
        query("SELECT nextval('atom_seq_cancel_pre')")
        query("SELECT nextval('atom_seq_cancel_pre')")
        val initialLastValue = query("SELECT last_value FROM atom_seq_cancel_pre")

        val ref = pgRef("atom_seq_cancel_pre")
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf("SELECT setval('atom_seq_cancel_pre', ${probe.value}, ${probe.isCalled})")
                },
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = emptyList(),
        )
        val tokenSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
        tokenSource.cancel("pre-BEGIN-test-cancel")

        conn().use { c ->
            c.autoCommit shouldBe true
            val result = executor.execute(
                connection = c,
                batch = batch,
                lockTimeoutMillis = 5_000,
                cancellationToken = tokenSource.token,
                executeProtectedOperations = { _, _ ->
                    error("protected callback must not be reached after pre-BEGIN cancel")
                },
            )
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
            result.reason shouldBe "pre-BEGIN-test-cancel"
            result.refs shouldBe listOf(ref)
            // Pre-BEGIN cancel: connection should be in its borrow-time
            // autocommit state because the executor never touched it.
            c.autoCommit shouldBe true
        }
        // Sequence completely untouched.
        query("SELECT last_value FROM atom_seq_cancel_pre") shouldBe initialLastValue
    }

    test("Cancelled: cancel inside protected callback rolls back the transaction (PG nextval-bump persists per PG semantics)") {
        exec("DROP SEQUENCE IF EXISTS atom_seq_cancel_mid")
        exec("CREATE SEQUENCE atom_seq_cancel_mid START WITH 1")
        query("SELECT nextval('atom_seq_cancel_mid')")
        query("SELECT nextval('atom_seq_cancel_mid')")

        val ref = pgRef("atom_seq_cancel_mid")
        val tokenSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    // This restore SHOULD never execute because we
                    // cancel in the protected callback (checkpoint 3).
                    error("renderRestore must not run after a post-protected cancel; probe=${probe.value}")
                },
            ),
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = emptyList(),
        )

        conn().use { c ->
            val result = executor.execute(
                connection = c,
                batch = batch,
                lockTimeoutMillis = 5_000,
                cancellationToken = tokenSource.token,
                executeProtectedOperations = { protectedConn, _ ->
                    protectedConn.createStatement().use { it.execute("SELECT nextval('atom_seq_cancel_mid')") }
                    tokenSource.cancel("post-protected-test-cancel")
                    AtomicProtectedExecutionResult.Succeeded(1)
                },
            )
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
            result.reason shouldBe "post-protected-test-cancel"
            result.refs shouldBe listOf(ref)
            c.autoCommit shouldBe true
        }
    }
})
