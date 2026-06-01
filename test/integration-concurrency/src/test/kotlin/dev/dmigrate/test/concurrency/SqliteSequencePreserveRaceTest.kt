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
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
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
        val url = jdbcUrl(tmpDbFile)

        val writerStart = CountDownLatch(1)
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

        openConnection(url).use { atomicConn ->
            val result = executor.execute(atomicConn, batch, lockTimeoutMillis = 30_000L) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
            }
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        }

        writerThread.join(TimeUnit.SECONDS.toMillis(30))
        writerThread.isAlive shouldBe false

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
    }
})

private fun jdbcUrl(path: Path): String = "jdbc:sqlite:${path.absolutePathString()}"

private fun openConnection(url: String): Connection {
    Class.forName("org.sqlite.JDBC")
    return DriverManager.getConnection(url).apply { autoCommit = true }
}
