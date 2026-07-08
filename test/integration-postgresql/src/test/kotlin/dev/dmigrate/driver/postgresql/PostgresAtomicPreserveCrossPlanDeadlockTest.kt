package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.connection.JdbcDatabaseConnection

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
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Atomic-Preserve Phase D (2026-06-01): cross-plan-deadlock-proof for
 * the PostgreSQL atomic-preserve executor. Two parallel `schema
 * migrate` runs that touch overlapping sequence sets must serialise
 * on the per-sequence advisory lock without diamond-deadlocking.
 *
 * The proof has two halves:
 *
 * - **Positive** — two threads call the executor concurrently with
 *   overlapping sequence batches (`[a, b]` and `[b, c]`). The
 *   executor's deterministic name-sort guarantees both threads take
 *   the locks in the same order (a → b → c), so the worst case is
 *   sequential commit, never deadlock. Both must reach `Applied`
 *   within the budget.
 * - **Negative smoke** — bypass the executor and acquire the same
 *   advisory locks **manually** in inverted order from two threads.
 *   This is the deadlock the sort prevents; the test confirms PG's
 *   `lock_timeout` fires on one of the threads, proving the
 *   primitive can deadlock and therefore the executor's sort is the
 *   thing that closes it.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase D ("Cross-Plan-Deadlock-Beweis pro Dialekt").
 */
class PostgresAtomicPreserveCrossPlanDeadlockTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("atomic_xplan_it")
        .withUsername("test")
        .withPassword("test")

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun openConn() = DriverManager.getConnection(
        container.jdbcUrl, container.username, container.password,
    )

    fun exec(sql: String) {
        openConn().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun query(sql: String): Long = openConn().use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                check(rs.next()) { "expected a row from $sql" }
                rs.getLong(1)
            }
        }
    }

    fun pgRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.POSTGRESQL)

    val executor = PostgresAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    test("Cross-plan: two parallel preserve runs with overlapping sequences commit without deadlock") {
        listOf("xplan_a", "xplan_b", "xplan_c").forEach {
            exec("DROP SEQUENCE IF EXISTS $it")
            exec("CREATE SEQUENCE $it START WITH 1")
            query("SELECT nextval('$it')")
        }

        fun buildBatch(names: List<String>): AtomicSequencePreserveBatch =
            AtomicSequencePreserveBatch(
                requests = names.map { name ->
                    AtomicSequencePreserveRequest(pgRef(name)) { probe ->
                        listOf("SELECT setval('$name', ${probe.value}, ${probe.isCalled})")
                    }
                },
                protectedOperationIds = listOf(protectedOpId),
                internalFollowUpIds = listOf("op-xplan-${names.joinToString("-")}"),
            )

        // Plan 1: [a, b] in caller order (executor still name-sorts).
        // Plan 2: [c, b] — overlaps on `b`, the deadlock candidate.
        val plan1Batch = buildBatch(listOf("xplan_a", "xplan_b"))
        val plan2Batch = buildBatch(listOf("xplan_c", "xplan_b"))

        val plan1Started = CountDownLatch(1)
        val plan2Started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val plan1Result = AtomicReference<AtomicSequencePreserveResult?>()
        val plan2Result = AtomicReference<AtomicSequencePreserveResult?>()
        try {
            val f1 = pool.submit<Unit> {
                openConn().use { c ->
                    plan1Started.countDown()
                    plan2Started.await(10, TimeUnit.SECONDS)
                    plan1Result.set(
                        executor.execute(JdbcDatabaseConnection(c), plan1Batch, lockTimeoutMillis = 15_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            val f2 = pool.submit<Unit> {
                openConn().use { c ->
                    plan2Started.countDown()
                    plan1Started.await(10, TimeUnit.SECONDS)
                    plan2Result.set(
                        executor.execute(JdbcDatabaseConnection(c), plan2Batch, lockTimeoutMillis = 15_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            f1.get(30, TimeUnit.SECONDS)
            f2.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        plan1Result.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        plan2Result.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
    }

    test("Negative smoke: two threads acquire advisory locks in inverted order — deadlock_detected or lock_timeout fires") {
        // The executor always sorts; this test bypasses it to show
        // the primitive WILL deadlock if the caller doesn't sort.
        // Thread A: lock("seq_x") then lock("seq_y").
        // Thread B: lock("seq_y") then lock("seq_x").
        // PG's default `deadlock_timeout = 1s` runs the deadlock
        // detector before our `lock_timeout = 2s` fires, so the
        // primary outcome is SQLSTATE 40P01 (deadlock_detected) on
        // the loser; 55P03 (lock_not_available) is accepted as a
        // fallback if a timing edge-case keeps the detector from
        // firing.
        val lockKey = { name: String -> "d-migrate:seq:.$name" }

        val aFirstAcquired = CountDownLatch(1)
        val bFirstAcquired = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val aResult = AtomicReference<String?>()
        val bResult = AtomicReference<String?>()
        try {
            val fA = pool.submit<Unit> {
                openConn().use { c ->
                    c.autoCommit = false
                    c.createStatement().use { it.execute("SET LOCAL lock_timeout = '2000ms'") }
                    runCatching {
                        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").use { ps ->
                            ps.setString(1, lockKey("seq_x"))
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                        aFirstAcquired.countDown()
                        // Wait until B has its first lock; now the
                        // crossed acquisition below will deadlock and
                        // the lock_timeout fires after 2s.
                        bFirstAcquired.await(10, TimeUnit.SECONDS)
                        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").use { ps ->
                            ps.setString(1, lockKey("seq_y"))
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                    }.onFailure { t ->
                        aResult.set((t as? SQLException)?.sqlState ?: "no-sqlstate")
                    }.onSuccess { aResult.set("ok") }
                    runCatching { c.rollback() }
                }
            }
            val fB = pool.submit<Unit> {
                openConn().use { c ->
                    c.autoCommit = false
                    c.createStatement().use { it.execute("SET LOCAL lock_timeout = '2000ms'") }
                    runCatching {
                        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").use { ps ->
                            ps.setString(1, lockKey("seq_y"))
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                        bFirstAcquired.countDown()
                        aFirstAcquired.await(10, TimeUnit.SECONDS)
                        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?)::bigint)").use { ps ->
                            ps.setString(1, lockKey("seq_x"))
                            ps.executeQuery().use { rs -> rs.next() }
                        }
                    }.onFailure { t ->
                        bResult.set((t as? SQLException)?.sqlState ?: "no-sqlstate")
                    }.onSuccess { bResult.set("ok") }
                    runCatching { c.rollback() }
                }
            }
            fA.get(30, TimeUnit.SECONDS)
            fB.get(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        // At least one thread must hit SQLSTATE 40P01
        // (deadlock_detected) or 55P03 (lock_not_available) — the
        // inverted acquisition order is the textbook deadlock that
        // the executor's name-sort closes.
        val outcomes = listOf(aResult.get(), bResult.get())
        outcomes.any { it == "40P01" || it == "55P03" } shouldBe true
    }
})
