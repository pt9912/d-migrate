package dev.dmigrate.test.concurrency

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.mysql.MysqlAtomicSequencePreserveExecutor
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private val ConcurrencyTag = NamedTag("concurrency")

/**
 * Atomic-Preserve Phase C.5 (2026-06-01) migration of the legacy
 * MySQL race reproducer onto the atomic-preserve path. The old test
 * demonstrated the probe→restore race window in
 * `SequencePreserveStage`'s heutigem Pfad; this rewrite proves the
 * race is **closed** under the atomic executor:
 *
 * 1. Bootstrap a `dmg_sequences` row at `next_value = 100`.
 * 2. Spawn a writer thread that runs N concurrent UPDATE-advances on
 *    the same row.
 * 3. While the writer is racing, run
 *    [MysqlAtomicSequencePreserveExecutor] with a probe+restore
 *    batch. The executor's `SELECT … FOR UPDATE` row lock blocks the
 *    writer for the lock window, probes the current value, runs a
 *    no-op protected callback, and restores the **freshly probed**
 *    value (NOT a stale read).
 * 4. After both finish, assert the writer's advances are observable
 *    in the final value (`finalValue >= initial + writerAdvances`).
 *
 * The 0.9.7 non-atomic path would have written a stale value back,
 * losing the writer's progress — `finalValue == initial`. The
 * atomic path keeps the row-lock around probe + restore so the
 * probed value reflects the writer's progress up to the moment the
 * atomic transaction committed; the writer's subsequent advances
 * land on top.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.5.
 */
class MysqlSequencePreserveRaceTest : FunSpec({

    tags(ConcurrencyTag)

    val container = MySQLContainer("mysql:8.0")
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

    fun openConnection(): Connection {
        Class.forName("com.mysql.cj.jdbc.Driver")
        return DriverManager.getConnection(jdbcUrl!!, jdbcUser!!, jdbcPassword!!)
            .apply { autoCommit = true }
    }

    fun bootstrap(seqName: String, initialValue: Long) {
        openConnection().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS `dmg_sequences` (
                        `name` VARCHAR(64) PRIMARY KEY,
                        `next_value` BIGINT NOT NULL,
                        `managed_by` VARCHAR(32) NOT NULL DEFAULT 'd-migrate',
                        `format_version` VARCHAR(32) NOT NULL DEFAULT 'mysql-sequence-v1'
                    ) ENGINE=InnoDB
                    """.trimIndent(),
                )
                stmt.execute("DELETE FROM `dmg_sequences` WHERE `name` = '$seqName'")
                stmt.execute(
                    "INSERT INTO `dmg_sequences` (`name`, `next_value`) VALUES ('$seqName', $initialValue)",
                )
            }
        }
    }

    fun nextValueOf(seqName: String): Long = openConnection().use { c ->
        c.prepareStatement("SELECT `next_value` FROM `dmg_sequences` WHERE `name` = ?").use { stmt ->
            stmt.setString(1, seqName)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "expected one row for $seqName" }
                rs.getLong(1)
            }
        }
    }

    test("MySQL atomic-preserve serializes concurrent writers — no stale restore (race closed)") {
        val seqName = "race_atomic_mysql"
        val initial = 100L
        val writerAdvances = 50
        bootstrap(seqName, initial)

        // Writer thread: 50 concurrent advances. Each advance is a
        // self-contained transaction so the atomic-preserve executor
        // can interleave its lock+probe+restore on the same row.
        val writerStart = CountDownLatch(1)
        val writerThread = thread(start = true, name = "mysql-race-writer") {
            writerStart.await(10, TimeUnit.SECONDS)
            repeat(writerAdvances) {
                openConnection().use { c ->
                    c.prepareStatement(
                        "UPDATE `dmg_sequences` SET `next_value` = `next_value` + 1 WHERE `name` = ?",
                    ).use { stmt ->
                        stmt.setString(1, seqName)
                        stmt.executeUpdate()
                    }
                }
                // Brief pause so the atomic executor below has a
                // realistic chance to interleave its lock window
                // somewhere in the middle of the writer's loop.
                Thread.sleep(2)
            }
        }

        // Atomic-preserve flow on its own connection. The probe reads
        // the live row INSIDE the lock; the restore writes that exact
        // value back. If the writer advanced before the lock, the
        // probe sees the advanced value and restore is a no-op; if
        // the writer advanced after the lock, it sees the restored
        // value and proceeds.
        val executor = MysqlAtomicSequencePreserveExecutor()
        val ref = SequenceObjectRef(seqName, null, RenameProjectionDialect.MYSQL)
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf(
                        "UPDATE `dmg_sequences` SET `next_value` = ${probe.value} " +
                            "WHERE `name` = '$seqName' AND `managed_by` = 'd-migrate' " +
                            "AND `format_version` = 'mysql-sequence-v1'",
                    )
                },
            ),
            protectedOperationIds = listOf(ProtectedOperationId("atomic-preserve-protected-op")),
            internalFollowUpIds = listOf("atomic-preserve-followup-op"),
        )
        writerStart.countDown()

        openConnection().use { atomicConn ->
            val result = executor.execute(atomicConn, batch, lockTimeoutMillis = 30_000L) { _, _ ->
                // No-op protected callback. The race-closure proof
                // does not depend on what runs inside the protected
                // window — it depends on the row lock blocking the
                // writer for the duration of probe+restore.
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        }

        writerThread.join(TimeUnit.SECONDS.toMillis(30))
        writerThread.isAlive shouldBe false

        // Race closed: the writer's advances are preserved. In the
        // legacy non-atomic path the final value would equal
        // `initial` (stale restore overwrote all writer progress).
        val finalValue = nextValueOf(seqName)
        finalValue shouldBeGreaterThanOrEqual (initial + writerAdvances)
    }
})
