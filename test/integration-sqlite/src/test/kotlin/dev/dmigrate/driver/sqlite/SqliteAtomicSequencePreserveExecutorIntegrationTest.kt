package dev.dmigrate.driver.sqlite

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
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * Atomic-Preserve Phase B.4 (2026-05-31): live-SQLite coverage for
 * [SqliteAtomicSequencePreserveExecutor]. Uses a file-backed SQLite
 * DB so the lock-race test can open a second connection that sees
 * the first connection's RESERVED lock (a `:memory:` DB is private
 * to one connection and cannot reproduce the contention).
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase B DoD.
 */
class SqliteAtomicSequencePreserveExecutorIntegrationTest : FunSpec({

    val executor = SqliteAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    fun sqliteRef(name: String) =
        SequenceObjectRef(name, null, RenameProjectionDialect.SQLITE)

    fun openConnection(path: Path): Connection =
        DriverManager.getConnection("jdbc:sqlite:${path.absolutePathString()}")

    fun bootstrapHelperTable(path: Path, seedRows: Map<String, Long>) {
        openConnection(path).use { c ->
            c.autoCommit = true
            c.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS \"dmg_sequences\"")
                stmt.execute(
                    """
                    CREATE TABLE "dmg_sequences" (
                        "name" TEXT PRIMARY KEY NOT NULL,
                        "next_value" INTEGER NOT NULL,
                        "managed_by" TEXT NOT NULL,
                        "format_version" TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                for ((name, value) in seedRows) {
                    // SqliteSequenceNaming.MANAGED_BY / FORMAT_VERSION
                    // are `internal` to driver-sqlite; the IT module
                    // pins the wire constants directly so a future
                    // contract bump surfaces here, not in an
                    // unrelated test.
                    stmt.execute(
                        "INSERT INTO \"dmg_sequences\" (\"name\", \"next_value\", \"managed_by\", \"format_version\") " +
                            "VALUES ('$name', $value, 'd-migrate', 'sqlite-sequence-v1')",
                    )
                }
            }
        }
    }

    fun queryNextValue(path: Path, name: String): Long {
        openConnection(path).use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT \"next_value\" FROM \"dmg_sequences\" WHERE \"name\" = '$name'",
                ).use { rs ->
                    rs.next() shouldBe true
                    return rs.getLong(1)
                }
            }
        }
    }

    test("Applied: single-sequence batch locks the DB, runs protected ops, restores next_value") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_a" to 105L))
            val ref = sqliteRef("atom_seq_a")
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { probe ->
                        listOf(
                            "UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} " +
                                "WHERE \"name\" = 'atom_seq_a' AND \"managed_by\" = '${probe.managedBy}'",
                        )
                    },
                ),
                protectedOperationIds = listOf(protectedOpId),
                internalFollowUpIds = listOf("op-atom_seq_a"),
            )
            openConnection(dbFile).use { c ->
                val result = executor.execute(c, batch, lockTimeoutMillis = 5_000) { protectedConn, _ ->
                    protectedConn.createStatement().use { s ->
                        s.execute("UPDATE \"dmg_sequences\" SET \"next_value\" = 999 WHERE \"name\" = 'atom_seq_a'")
                    }
                    AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 1)
                }
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
                result.refs shouldBe listOf(ref)
            }
            // Restore overwrote the protected op's bump; the row holds the probed snapshot.
            queryNextValue(dbFile, "atom_seq_a") shouldBe 105L
        } finally {
            dbFile.deleteIfExists()
        }
    }

    test("Applied: multi-sequence batch sees deterministic name-sorted commit order") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-multi-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_a_multi" to 10L, "atom_seq_z" to 100L))
            val refA = sqliteRef("atom_seq_a_multi")
            val refZ = sqliteRef("atom_seq_z")
            val batch = AtomicSequencePreserveBatch(
                // Caller supplies refs in reverse alphabetical order;
                // the executor's sort still commits in (a, z) order.
                requests = listOf(
                    AtomicSequencePreserveRequest(refZ) { probe ->
                        listOf("UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} WHERE \"name\" = 'atom_seq_z'")
                    },
                    AtomicSequencePreserveRequest(refA) { probe ->
                        listOf("UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} WHERE \"name\" = 'atom_seq_a_multi'")
                    },
                ),
                protectedOperationIds = listOf(protectedOpId),
                internalFollowUpIds = listOf("op-multi"),
            )
            openConnection(dbFile).use { c ->
                val result = executor.execute(c, batch, lockTimeoutMillis = 5_000) { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
                }
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
                result.refs shouldBe listOf(refA, refZ)
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    test("NotFound: missing helper-table row rolls back the batch") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-missing-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_present" to 1L))
            val ref = sqliteRef("atom_seq_missing")
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { probe ->
                        listOf("UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} WHERE \"name\" = 'atom_seq_missing'")
                    },
                ),
                protectedOperationIds = emptyList(),
                internalFollowUpIds = emptyList(),
            )
            openConnection(dbFile).use { c ->
                val result = executor.execute(c, batch, lockTimeoutMillis = 1_000) { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(0)
                }
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
                result.refs shouldBe listOf(ref)
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    test("LockTimeout: concurrent BEGIN IMMEDIATE holder triggers SQLITE_BUSY") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-locked-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_locked" to 1L))
            // Open a holder connection that grabs the DB-wide
            // RESERVED lock and holds it for the test duration.
            openConnection(dbFile).use { holder ->
                holder.autoCommit = true
                holder.createStatement().use { it.execute("BEGIN IMMEDIATE") }
                try {
                    val ref = sqliteRef("atom_seq_locked")
                    val batch = AtomicSequencePreserveBatch(
                        requests = listOf(
                            AtomicSequencePreserveRequest(ref) { probe ->
                                listOf("UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} WHERE \"name\" = 'atom_seq_locked'")
                            },
                        ),
                        protectedOperationIds = emptyList(),
                        internalFollowUpIds = emptyList(),
                    )
                    openConnection(dbFile).use { c ->
                        val result = executor.execute(c, batch, lockTimeoutMillis = 200) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        }
                        result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
                        result.refs shouldBe listOf(ref)
                    }
                } finally {
                    holder.createStatement().use { it.execute("ROLLBACK") }
                }
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    test("Session busy_timeout is restored to its borrow-time value (no pool leak)") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-timeout-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_timeout_leak" to 1L))
            val ref = sqliteRef("atom_seq_timeout_leak")
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { probe ->
                        listOf("UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} WHERE \"name\" = 'atom_seq_timeout_leak'")
                    },
                ),
                protectedOperationIds = emptyList(),
                internalFollowUpIds = emptyList(),
            )
            openConnection(dbFile).use { c ->
                // Set a deliberate non-default busy_timeout so the
                // restore step is observable (default is 0 → 0 →
                // would pass the test trivially).
                c.createStatement().use { it.execute("PRAGMA busy_timeout = 7777") }
                val before = c.createStatement().use { s ->
                    s.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next(); rs.getLong(1)
                    }
                }
                before shouldBe 7777L
                val result = executor.execute(c, batch, lockTimeoutMillis = 1_000) { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(0)
                }
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
                val after = c.createStatement().use { s ->
                    s.executeQuery("PRAGMA busy_timeout").use { rs ->
                        rs.next(); rs.getLong(1)
                    }
                }
                after shouldBe before
            }
        } finally {
            dbFile.deleteIfExists()
        }
    }

    // ─── Service-Mode Sub-Slice E: Cancellation ─────────────────────

    test("Cancelled: pre-BEGIN cancel short-circuits without acquiring RESERVED lock") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-cancel-pre-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_cancel_pre" to 142L))
            val initialNextValue = queryNextValue(dbFile, "atom_seq_cancel_pre")
            val ref = sqliteRef("atom_seq_cancel_pre")
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { probe ->
                        listOf(
                            "UPDATE \"dmg_sequences\" SET \"next_value\" = ${probe.value} " +
                                "WHERE \"name\" = 'atom_seq_cancel_pre'",
                        )
                    },
                ),
                protectedOperationIds = listOf(protectedOpId),
                internalFollowUpIds = emptyList(),
            )
            val tokenSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
            tokenSource.cancel("pre-BEGIN-test-cancel")

            openConnection(dbFile).use { c ->
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
            }
            queryNextValue(dbFile, "atom_seq_cancel_pre") shouldBe initialNextValue
        } finally {
            dbFile.deleteIfExists()
        }
    }

    test("Cancelled: cancel inside protected callback rolls back; helper-table next_value at probed snapshot") {
        val dbFile = Files.createTempFile("atomic-preserve-sqlite-cancel-mid-", ".db")
        try {
            bootstrapHelperTable(dbFile, mapOf("atom_seq_cancel_mid" to 142L))
            val initialNextValue = queryNextValue(dbFile, "atom_seq_cancel_mid")
            val ref = sqliteRef("atom_seq_cancel_mid")
            val tokenSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
            val batch = AtomicSequencePreserveBatch(
                requests = listOf(
                    AtomicSequencePreserveRequest(ref) { _ ->
                        error("renderRestore must not run after a post-protected cancel")
                    },
                ),
                protectedOperationIds = listOf(protectedOpId),
                internalFollowUpIds = emptyList(),
            )

            openConnection(dbFile).use { c ->
                val result = executor.execute(
                    connection = c,
                    batch = batch,
                    lockTimeoutMillis = 5_000,
                    cancellationToken = tokenSource.token,
                    executeProtectedOperations = { protectedConn, _ ->
                        protectedConn.createStatement().use {
                            it.execute("UPDATE \"dmg_sequences\" SET \"next_value\" = 999 WHERE \"name\" = 'atom_seq_cancel_mid'")
                        }
                        tokenSource.cancel("post-protected-test-cancel")
                        AtomicProtectedExecutionResult.Succeeded(1)
                    },
                )
                result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
                result.reason shouldBe "post-protected-test-cancel"
                result.refs shouldBe listOf(ref)
            }
            // SQLite ROLLBACK undoes both the protected UPDATE and any
            // RESERVED-lock side effect; next_value returns to the
            // probed snapshot.
            queryNextValue(dbFile, "atom_seq_cancel_mid") shouldBe initialNextValue
        } finally {
            dbFile.deleteIfExists()
        }
    }
})
