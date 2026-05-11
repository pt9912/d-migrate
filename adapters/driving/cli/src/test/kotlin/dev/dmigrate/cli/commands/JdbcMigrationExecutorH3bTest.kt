package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * Phase H.3b: runner-hook marker parsing + side-effect application
 * in [JdbcMigrationExecutor]. Unit-level coverage of [parseRunnerHook]
 * and [executeOrApplyHook]; live-DB end-to-end coverage lives in
 * `:test:integration-sqlite`.
 */
class JdbcMigrationExecutorH3bTest : FunSpec({

    test("parseRunnerHook returns the hook name for a save marker") {
        JdbcMigrationExecutor.parseRunnerHook("-- dmigrate:runner-hook=save-fk-state-before-pragma-off") shouldBe
            "save-fk-state-before-pragma-off"
    }

    test("parseRunnerHook returns the hook name for a restore marker") {
        JdbcMigrationExecutor.parseRunnerHook("-- dmigrate:runner-hook=restore-fk-state") shouldBe
            "restore-fk-state"
    }

    test("parseRunnerHook returns null for a regular SQL statement") {
        JdbcMigrationExecutor.parseRunnerHook("PRAGMA foreign_keys = OFF;") shouldBe null
        JdbcMigrationExecutor.parseRunnerHook("BEGIN IMMEDIATE;") shouldBe null
        JdbcMigrationExecutor.parseRunnerHook("CREATE TABLE x (id INTEGER);") shouldBe null
    }

    test("parseRunnerHook returns null for non-dmigrate comments") {
        JdbcMigrationExecutor.parseRunnerHook("-- regular comment") shouldBe null
        JdbcMigrationExecutor.parseRunnerHook("-- d-migrate rebuild: header") shouldBe null
    }

    test("parseRunnerHook tolerates leading whitespace") {
        JdbcMigrationExecutor.parseRunnerHook(
            "   -- dmigrate:runner-hook=restore-fk-state",
        ) shouldBe "restore-fk-state"
    }

    test("executeOrApplyHook with save-fk-state: queries PRAGMA foreign_keys and stores the result") {
        val conn = mockk<Connection>()
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val pragmaStmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns pragmaStmt
        every { pragmaStmt.executeQuery("PRAGMA foreign_keys;") } returns rs
        every { rs.next() } returns true
        every { rs.getInt(1) } returns 0 // prior state was OFF

        val state = JdbcMigrationExecutor.RunnerHookState()
        JdbcMigrationExecutor.executeOrApplyHook(
            conn = conn,
            jdbcStmt = jdbcStmt,
            sql = "-- dmigrate:runner-hook=save-fk-state-before-pragma-off",
            state = state,
        )

        state.savedSqliteForeignKeysPragma shouldBe 0
        verify(exactly = 0) { jdbcStmt.execute(any<String>()) }
    }

    test("executeOrApplyHook with restore-fk-state: emits PRAGMA foreign_keys = <saved>") {
        val conn = mockk<Connection>()
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val restoreStmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns restoreStmt

        val state = JdbcMigrationExecutor.RunnerHookState(savedSqliteForeignKeysPragma = 0)
        JdbcMigrationExecutor.executeOrApplyHook(
            conn = conn,
            jdbcStmt = jdbcStmt,
            sql = "-- dmigrate:runner-hook=restore-fk-state",
            state = state,
        )

        verifySequence { conn.createStatement(); restoreStmt.execute("PRAGMA foreign_keys = 0;"); restoreStmt.close() }
        verify(exactly = 0) { jdbcStmt.execute(any<String>()) }
    }

    test("executeOrApplyHook restore without prior save defaults to ON (1)") {
        // Defensive: if the renderer somehow emits restore without save,
        // restore to ON — the safer post-migration default.
        val conn = mockk<Connection>()
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val restoreStmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns restoreStmt

        val state = JdbcMigrationExecutor.RunnerHookState() // no prior save
        JdbcMigrationExecutor.executeOrApplyHook(
            conn = conn,
            jdbcStmt = jdbcStmt,
            sql = "-- dmigrate:runner-hook=restore-fk-state",
            state = state,
        )

        verify { restoreStmt.execute("PRAGMA foreign_keys = 1;") }
    }

    test("executeOrApplyHook with a plain SQL statement delegates to jdbcStmt.execute") {
        val conn = mockk<Connection>(relaxed = true)
        val jdbcStmt = mockk<Statement>(relaxed = true)

        val state = JdbcMigrationExecutor.RunnerHookState()
        JdbcMigrationExecutor.executeOrApplyHook(
            conn = conn,
            jdbcStmt = jdbcStmt,
            sql = "CREATE TABLE users (id INTEGER PRIMARY KEY);",
            state = state,
        )

        verify { jdbcStmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY);") }
        state.savedSqliteForeignKeysPragma shouldBe null
    }

    test("end-to-end with embedded SQLite: PRAGMA OFF → BEGIN/COMMIT → restore reads back as OFF") {
        // The hook contract: on a real SQLite connection where PRAGMA
        // foreign_keys is OFF at start, the save-hook captures 0, the
        // rebuild runs with PRAGMA = OFF, and the restore-hook returns
        // PRAGMA to 0 (not the renderer's pauschal 1).
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF;") }

            val state = JdbcMigrationExecutor.RunnerHookState()
            conn.createStatement().use { stmt ->
                JdbcMigrationExecutor.executeOrApplyHook(
                    conn, stmt,
                    "-- dmigrate:runner-hook=save-fk-state-before-pragma-off",
                    state,
                )
                stmt.execute("PRAGMA foreign_keys = OFF;")
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY);")
                JdbcMigrationExecutor.executeOrApplyHook(
                    conn, stmt,
                    "-- dmigrate:runner-hook=restore-fk-state",
                    state,
                )
            }

            state.savedSqliteForeignKeysPragma shouldBe 0
            // Verify the connection's FK state is restored.
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA foreign_keys;").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 0
                }
            }
        } finally {
            conn.close()
        }
    }

    test("end-to-end with embedded SQLite: prior ON state is preserved across save/restore cycle") {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON;") }

            val state = JdbcMigrationExecutor.RunnerHookState()
            conn.createStatement().use { stmt ->
                JdbcMigrationExecutor.executeOrApplyHook(
                    conn, stmt,
                    "-- dmigrate:runner-hook=save-fk-state-before-pragma-off",
                    state,
                )
                JdbcMigrationExecutor.executeOrApplyHook(
                    conn, stmt,
                    "-- dmigrate:runner-hook=restore-fk-state",
                    state,
                )
            }

            state.savedSqliteForeignKeysPragma shouldBe 1
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA foreign_keys;").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        } finally {
            conn.close()
        }
    }

    test("executeOrApplyHook throws on an unknown hook name") {
        val conn = mockk<Connection>(relaxed = true)
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val state = JdbcMigrationExecutor.RunnerHookState()

        try {
            JdbcMigrationExecutor.executeOrApplyHook(
                conn = conn,
                jdbcStmt = jdbcStmt,
                sql = "-- dmigrate:runner-hook=nonsense-hook-name",
                state = state,
            )
            throw AssertionError("expected IllegalStateException for unknown hook")
        } catch (e: IllegalStateException) {
            (e.message ?: "").contains("unknown runner-hook") shouldBe true
        }
    }
})
