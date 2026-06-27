package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

/**
 * Deterministic JDBC-level unit coverage for
 * [SqliteAtomicSequencePreserveExecutor] against a real file-backed SQLite
 * database.
 *
 * The executor's lock / probe / restore / COMMIT branches were previously
 * exercised only by the live-DB integration suite (`test/integration-sqlite`),
 * whose coverage does **not** count toward this module's per-module Kover gate
 * — only the owner-check require was pinned as a unit test. These tests drive
 * the BEGIN-IMMEDIATE / `busy_timeout` / `LockTimeout` / `Cancelled` /
 * probe-`NotFound` / protected-op-failure paths without a cross-module
 * dependency, so the module clears 90% deterministically instead of riding the
 * SQLITE_BUSY-timing line.
 */
class SqliteAtomicSequencePreserveExecutorTest : FunSpec({

    lateinit var dbFile: Path
    lateinit var conn: Connection

    fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePathString()}")

    beforeEach {
        dbFile = Files.createTempFile("d-migrate-atomic-", ".db")
        dbFile.deleteIfExists()
        conn = openConnection()
        conn.createStatement().use { stmt ->
            stmt.execute(
                "CREATE TABLE dmg_sequences (name TEXT PRIMARY KEY, next_value INTEGER NOT NULL, " +
                    "managed_by TEXT NOT NULL, format_version TEXT NOT NULL)",
            )
            stmt.execute(
                "INSERT INTO dmg_sequences (name, next_value, managed_by, format_version) " +
                    "VALUES ('order_seq', 100, 'd-migrate', 'sqlite-sequence-v1')",
            )
        }
    }

    afterEach {
        runCatching { conn.close() }
        dbFile.deleteIfExists()
    }

    val executor = SqliteAtomicSequencePreserveExecutor()

    fun ref(name: String = "order_seq") =
        SequenceObjectRef(name = name, schema = null, dialect = RenameProjectionDialect.SQLITE)

    fun batchFor(name: String = "order_seq") = AtomicSequencePreserveBatch(
        requests = listOf(AtomicSequencePreserveRequest(ref(name)) { _ -> emptyList() }),
        protectedOperationIds = emptyList(),
        internalFollowUpIds = emptyList(),
    )

    val succeedProtected = { _: DatabaseConnection, _: List<*> ->
        AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
    }

    test("applied: BEGIN IMMEDIATE → probe Read → protected ops → restore → COMMIT") {
        val result = executor.execute(
            connection = JdbcDatabaseConnection(conn),
            batch = batchFor(),
            lockTimeoutMillis = 5_000L,
            executeProtectedOperations = { c, ids -> succeedProtected(c, ids) },
        )
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
    }

    test("probe NotFound when the helper-table row is absent → NotFound + rollback") {
        val result = executor.execute(
            connection = JdbcDatabaseConnection(conn),
            batch = batchFor("absent_seq"),
            lockTimeoutMillis = 5_000L,
            executeProtectedOperations = { c, ids -> succeedProtected(c, ids) },
        )
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
    }

    test("protected-operation exception → Failed with rollback") {
        val result = executor.execute(
            connection = JdbcDatabaseConnection(conn),
            batch = batchFor(),
            lockTimeoutMillis = 5_000L,
            executeProtectedOperations = { _, _ -> throw IllegalStateException("boom") },
        )
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
    }

    test("already-cancelled token short-circuits to Cancelled before BEGIN IMMEDIATE") {
        val source = CancellationTokenSource.create()
        source.cancel("test-cancel")
        val result = executor.execute(
            connection = JdbcDatabaseConnection(conn),
            batch = batchFor(),
            lockTimeoutMillis = 5_000L,
            cancellationToken = source.token,
            executeProtectedOperations = { c, ids -> succeedProtected(c, ids) },
        )
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
    }

    test("SQLITE_BUSY on BEGIN IMMEDIATE (RESERVED lock held elsewhere) → LockTimeout") {
        val blocker = openConnection()
        try {
            blocker.autoCommit = true
            blocker.createStatement().use { it.execute("BEGIN IMMEDIATE") }
            val result = executor.execute(
                connection = JdbcDatabaseConnection(conn),
                batch = batchFor(),
                lockTimeoutMillis = 100L,
                executeProtectedOperations = { c, ids -> succeedProtected(c, ids) },
            )
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
        } finally {
            runCatching { blocker.createStatement().use { it.execute("ROLLBACK") } }
            runCatching { blocker.close() }
        }
    }

    test("empty batch short-circuits to Applied before the owner check") {
        val result = executor.execute(
            connection = JdbcDatabaseConnection(conn),
            batch = AtomicSequencePreserveBatch(
                requests = emptyList(),
                protectedOperationIds = emptyList(),
                internalFollowUpIds = emptyList(),
            ),
            lockTimeoutMillis = 5_000L,
            executeProtectedOperations = { c, ids -> succeedProtected(c, ids) },
        )
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
    }

    test("non-positive lockTimeoutMillis is rejected") {
        shouldThrow<IllegalArgumentException> {
            executor.execute(
                connection = JdbcDatabaseConnection(conn),
                batch = batchFor(),
                lockTimeoutMillis = 0L,
                executeProtectedOperations = { c, ids -> succeedProtected(c, ids) },
            )
        }
    }
})
