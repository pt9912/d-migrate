package dev.dmigrate.test.concurrency

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.postgresql.PostgresAtomicSequencePreserveExecutor
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val ConcurrencyTag = NamedTag("concurrency")

/**
 * Atomic-Preserve Phase D follow-up (2026-06-01) migration of the
 * legacy PostgreSQL race reproducer onto the atomic-preserve path.
 *
 * Unlike [MysqlSequencePreserveRaceTest] and
 * [SqliteSequencePreserveRaceTest] — which migrated to a
 * `race-closed` proof because their per-row / DB-wide locks block
 * app-side writers — the PostgreSQL atomic-preserve path uses
 * `pg_advisory_xact_lock(hashtext(...))`. Advisory locks are by-
 * design **lock-free against app-side `nextval(...)`** calls: only
 * code that ALSO takes the same advisory key blocks. The atomic
 * executor's contract on PG is therefore "smaller race window than
 * the two-transaction fallback PLUS no inter-d-migrate corruption",
 * not "race closed".
 *
 * This spec migrates the legacy stale-restore reproducer onto the
 * production [PostgresAtomicSequencePreserveExecutor] and pins the
 * residual app-nextval race as a **documented carve-out** (Plan §6
 * Risiko Nr. 8 / User-Guide PG-Restrisiko). The assertion shape is
 * intentionally the inverse of the MySQL / SQLite race-closed tests:
 *
 * 1. Bootstrap a sequence at `last_value = 1`.
 * 2. Open the atomic executor on connection A. Its protected
 *    callback signals the writer thread (on connection B) and
 *    blocks until the writer has done N `nextval`-advances.
 * 3. The writer advances `last_value` from 1 to N+1 while the
 *    advisory lock is held — proof the advisory lock does NOT
 *    block app writers.
 * 4. The executor's restore writes `setval(seq, 1, true)` and
 *    commits. PG sequences are non-transactional, so `last_value`
 *    immediately reverts to 1 — the writer's N advances are
 *    overwritten.
 * 5. Final read confirms `last_value == 1` (stale-restore won) and
 *    the writer thread observed forward progress before the
 *    overwrite (writer wasn't blocked).
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.5 (race-test migration); §6 Risiko Nr. 8
 * (residual app-nextval race carve-out).
 */
class PostgresSequencePreserveRaceTest : FunSpec({

    tags(ConcurrencyTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("concurrency_test")
        .withUsername("concurrency")
        .withPassword("concurrency")

    var jdbcUrl: String? = null
    var jdbcUser: String? = null
    var jdbcPassword: String? = null

    beforeSpec {
        container.start()
        jdbcUrl = container.jdbcUrl
        jdbcUser = container.username
        jdbcPassword = container.password
    }

    afterSpec { container.stop() }

    fun openConn(): Connection {
        Class.forName("org.postgresql.Driver")
        return DriverManager.getConnection(jdbcUrl!!, jdbcUser!!, jdbcPassword!!).apply {
            autoCommit = true
        }
    }

    fun lastValue(seqName: String): Long = openConn().use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT last_value FROM $seqName").use { rs ->
                check(rs.next()) { "expected one row from SELECT last_value FROM $seqName" }
                rs.getLong(1)
            }
        }
    }

    test("PG atomic-preserve documents residual app-nextval race (Plan §6 Risk 8 — pg_advisory_xact_lock is app-blind)") {
        val seqName = "race_atomic_pg_residual"
        val writerAdvances = 50

        openConn().use { setup ->
            setup.createStatement().use { stmt ->
                stmt.execute("DROP SEQUENCE IF EXISTS $seqName")
                stmt.execute("CREATE SEQUENCE $seqName START WITH 1 INCREMENT BY 1")
                // Bring last_value to 1 by calling nextval once so
                // is_called = true (matches restore semantic below).
                stmt.execute("SELECT nextval('$seqName')")
            }
        }

        val writerStart = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        var writerObservedMax: Long = Long.MIN_VALUE
        val writerThread = thread(start = true, name = "pg-residual-race-writer") {
            check(writerStart.await(10, TimeUnit.SECONDS)) {
                "writer did not receive start signal"
            }
            var max = Long.MIN_VALUE
            repeat(writerAdvances) {
                openConn().use { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT nextval('$seqName')").use { rs ->
                            check(rs.next())
                            max = maxOf(max, rs.getLong(1))
                        }
                    }
                }
            }
            writerObservedMax = max
            writerFinished.countDown()
        }

        val executor = PostgresAtomicSequencePreserveExecutor()
        val ref = SequenceObjectRef(seqName, null, RenameProjectionDialect.POSTGRESQL)
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    // Render the restore using the probed value — the
                    // executor calls this after the protected
                    // callback returns. `probe.value` is 1 (the
                    // pre-race `last_value`), so the restore snaps
                    // the sequence back to 1 even though the writer
                    // advanced it past 51.
                    listOf("SELECT setval('$seqName', ${probe.value}, ${probe.isCalled})")
                },
            ),
            protectedOperationIds = listOf(ProtectedOperationId("AlterSequenceCurrentValue")),
            internalFollowUpIds = emptyList(),
        )

        openConn().use { atomicConn ->
            val result = executor.execute(atomicConn, batch, lockTimeoutMillis = 30_000L) { _, _ ->
                // Inside the protected window: signal the writer to
                // start its `nextval` loop, then wait until the
                // writer has finished all advances. Advisory locks
                // don't block the writer — that's the entire point
                // of this test.
                writerStart.countDown()
                check(writerFinished.await(20, TimeUnit.SECONDS)) {
                    "writer did not finish its advances within budget"
                }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        }

        writerThread.join(TimeUnit.SECONDS.toMillis(30))
        writerThread.isAlive shouldBe false

        // Two carve-out invariants:
        //
        // (1) Writer was NOT blocked by the executor's advisory lock
        //     — it observed forward progress on the sequence during
        //     the lock window. (For MySQL / SQLite the analogous
        //     assertion is `finalValue >= initial + writerAdvances`;
        //     here we only assert the writer advanced AT LEAST
        //     `initial + 1` because the restore will overwrite.)
        writerObservedMax shouldBeGreaterThanOrEqual (1L + writerAdvances)

        // (2) Restore snapped last_value back to the probed value
        //     (1), overwriting the writer's advances. PG sequences
        //     are non-transactional, so the setval(...) effect is
        //     visible immediately and persists on commit.
        val finalLastValue = lastValue(seqName)
        finalLastValue shouldBe 1L
    }
})
