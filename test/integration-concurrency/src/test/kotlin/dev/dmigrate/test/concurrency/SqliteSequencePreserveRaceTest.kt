package dev.dmigrate.test.concurrency

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.sqlite.SqliteAtomicSequencePreserveExecutor
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

private val ConcurrencyTag = NamedTag("concurrency")

/**
 * Atomic-Preserve Phase C.5 (2026-06-01) migration of the legacy
 * SQLite race reproducer onto the atomic-preserve path. Companion
 * to [MysqlSequencePreserveRaceTest]; SQLite's `BEGIN IMMEDIATE`
 * holds a database-wide `RESERVED` lock which blocks every concurrent
 * writer for the duration of the atomic probe+restore window.
 *
 * Plan-Doc: `docs/planning/done-archive/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.5.
 */
class SqliteSequencePreserveRaceTest : FunSpec({

    tags(ConcurrencyTag)

    val sequenceName = "race_atomic_sqlite"
    val tmpDbFile: Path = Files.createTempFile("dmigrate-concurrency-sqlite-", ".db")

    beforeSpec {
        tmpDbFile.deleteIfExists()
        Files.createFile(tmpDbFile)
        openConnection(jdbcUrl(tmpDbFile)).use { setup ->
            setup.autoCommit = true
            setup.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS "dmg_sequences" (
                        "name" TEXT PRIMARY KEY NOT NULL,
                        "next_value" INTEGER NOT NULL,
                        "managed_by" TEXT NOT NULL DEFAULT 'd-migrate',
                        "format_version" TEXT NOT NULL DEFAULT 'sqlite-sequence-v1'
                    )
                    """.trimIndent(),
                )
                stmt.execute("DELETE FROM \"dmg_sequences\" WHERE \"name\" = '$sequenceName'")
                stmt.execute(
                    "INSERT INTO \"dmg_sequences\" (\"name\", \"next_value\") VALUES ('$sequenceName', 100)",
                )
            }
        }
    }

    afterSpec { tmpDbFile.deleteIfExists() }

    test("SQLite atomic-preserve serializes concurrent writers — no stale restore (race closed)") {
        val initial = 100L
        val writerAdvances = 50
        val lockWindowSleepMillis = 500L
        val url = jdbcUrl(tmpDbFile)

        val writerStart = CountDownLatch(1)
        val writerAdvancesDone = java.util.concurrent.atomic.AtomicInteger(0)
        val writerThread = thread(start = true, name = "sqlite-race-writer") {
            writerStart.await(10, TimeUnit.SECONDS)
            repeat(writerAdvances) {
                openConnection(url).use { c ->
                    c.prepareStatement(
                        "UPDATE \"dmg_sequences\" SET \"next_value\" = \"next_value\" + 1 WHERE \"name\" = ?",
                    ).use { stmt ->
                        stmt.setString(1, sequenceName)
                        stmt.executeUpdate()
                    }
                }
                writerAdvancesDone.incrementAndGet()
                Thread.sleep(2)
            }
        }

        val executor = SqliteAtomicSequencePreserveExecutor()
        val ref = SequenceObjectRef(sequenceName, null, RenameProjectionDialect.SQLITE)
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(ref) { probe ->
                    listOf(
                        "UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} " +
                            "WHERE \"name\" = '$sequenceName'",
                    )
                },
            ),
            protectedOperationIds = listOf(ProtectedOperationId("atomic-preserve-protected-op")),
            internalFollowUpIds = listOf("atomic-preserve-followup-op"),
        )
        writerStart.countDown()

        // Finding #5 (2026-06-01): capture writer-advance counters at
        // the start and end of the lock window so the test can prove
        // SQLite's RESERVED lock (acquired by BEGIN IMMEDIATE) blocks
        // the writer for the duration of the protected callback.
        val advancesAtLockStart = java.util.concurrent.atomic.AtomicInteger(-1)
        val advancesAtLockEnd = java.util.concurrent.atomic.AtomicInteger(-1)

        openConnection(url).use { atomicConn ->
            val result = executor.execute(atomicConn, batch, lockTimeoutMillis = 30_000L) { _, _ ->
                // The SQLite executor has already issued BEGIN
                // IMMEDIATE before this callback runs; the RESERVED
                // lock is held until commit. Any writer UPDATE on a
                // different connection blocks until the lock
                // releases.
                advancesAtLockStart.set(writerAdvancesDone.get())
                Thread.sleep(lockWindowSleepMillis)
                advancesAtLockEnd.set(writerAdvancesDone.get())
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        }

        writerThread.join(TimeUnit.SECONDS.toMillis(30))
        writerThread.isAlive shouldBe false

        // (1) Race closed: the writer's advances are preserved.
        val finalValue = openConnection(url).use { c ->
            c.prepareStatement(
                "SELECT \"next_value\" FROM \"dmg_sequences\" WHERE \"name\" = ?",
            ).use { stmt ->
                stmt.setString(1, sequenceName)
                stmt.executeQuery().use { rs ->
                    check(rs.next())
                    rs.getLong(1)
                }
            }
        }
        finalValue shouldBeGreaterThanOrEqual (initial + writerAdvances)

        // (2) Finding #5 strengthening: during the 500 ms RESERVED
        //     lock window the writer made ZERO additional advances.
        //     Without the lock, a 2 ms-pause writer would slip ~250
        //     UPDATEs through a 500 ms window. Zero is the only
        //     passing observation.
        (advancesAtLockEnd.get() - advancesAtLockStart.get()) shouldBe 0
    }
})

private fun jdbcUrl(path: Path): String = "jdbc:sqlite:${path.absolutePathString()}"

private fun openConnection(url: String): Connection {
    Class.forName("org.sqlite.JDBC")
    return DriverManager.getConnection(url).apply { autoCommit = true }
}
