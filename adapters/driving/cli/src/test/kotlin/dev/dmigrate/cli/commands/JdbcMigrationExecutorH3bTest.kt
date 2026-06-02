package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

// Every ddl() call in this file builds a SQLite-rebuild-shaped stream
// (PRAGMA wrapper + BEGIN/COMMIT bracket), which the renderer marks as
// STREAM_OWNED so JdbcMigrationExecutor picks the stream-owned-tx
// dispatch path. Plan-2 §G.1: that dispatch reads
// MigrationDdlStatement.transactionScope, not the SQL body — so this
// test fixture must stamp the field explicitly.
private fun ddl(sql: String): MigrationDdlStatement =
    MigrationDdlStatement(
        sql = sql,
        operationIds = emptySet(),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
        transactionScope = TransactionScope.STREAM_OWNED,
    )

/**
 * Phase H.3b: runner-hook marker parsing + side-effect application
 * via the shared [RunnerHookHandler] (hexagon:application). Both the
 * production [JdbcMigrationExecutor] and the
 * `MigrationExecutorTestSupport` test-fixture call into the same
 * handler so the H.3b contract is uniformly enforced.
 */
class JdbcMigrationExecutorH3bTest : FunSpec({

    test("parseHook returns the hook name for a save marker") {
        RunnerHookHandler.parseHook("-- dmigrate:runner-hook=save-fk-state-before-pragma-off") shouldBe
            "save-fk-state-before-pragma-off"
    }

    test("parseHook returns the hook name for a restore marker") {
        RunnerHookHandler.parseHook("-- dmigrate:runner-hook=restore-fk-state") shouldBe
            "restore-fk-state"
    }

    test("parseHook returns null for a regular SQL statement") {
        RunnerHookHandler.parseHook("PRAGMA foreign_keys = OFF;") shouldBe null
        RunnerHookHandler.parseHook("BEGIN IMMEDIATE;") shouldBe null
        RunnerHookHandler.parseHook("CREATE TABLE x (id INTEGER);") shouldBe null
    }

    test("parseHook returns null for non-dmigrate comments") {
        RunnerHookHandler.parseHook("-- regular comment") shouldBe null
        RunnerHookHandler.parseHook("-- d-migrate rebuild: header") shouldBe null
    }

    test("parseHook tolerates leading whitespace") {
        RunnerHookHandler.parseHook("   -- dmigrate:runner-hook=restore-fk-state") shouldBe "restore-fk-state"
    }

    test("parseHook returns null for unrecognised hook names (allowlist)") {
        // Allowlist drift protection: an unknown hook is treated as a
        // regular comment, not as an error. The JDBC driver ignores it.
        RunnerHookHandler.parseHook("-- dmigrate:runner-hook=nonsense") shouldBe null
        RunnerHookHandler.parseHook("-- dmigrate:runner-hook=restore-fk-state ; DROP TABLE foo") shouldBe null
    }

    test("apply with save-fk-state: reads PRAGMA via jdbcStmt (no sibling Statement)") {
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { jdbcStmt.executeQuery("PRAGMA foreign_keys;") } returns rs
        every { rs.next() } returns true
        every { rs.getInt(1) } returns 0

        val state = RunnerHookHandler.State()
        RunnerHookHandler.apply(jdbcStmt, "save-fk-state-before-pragma-off", state)

        state.savedSqliteForeignKeysPragma shouldBe 0
        verify(exactly = 0) { jdbcStmt.execute(any<String>()) }
    }

    test("apply with restore-fk-state: emits PRAGMA via jdbcStmt") {
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val state = RunnerHookHandler.State(savedSqliteForeignKeysPragma = 0)

        RunnerHookHandler.apply(jdbcStmt, "restore-fk-state", state)

        verify { jdbcStmt.execute("PRAGMA foreign_keys = 0;") }
    }

    test("apply restore without prior save defaults to ON (1)") {
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val state = RunnerHookHandler.State() // no prior save

        RunnerHookHandler.apply(jdbcStmt, "restore-fk-state", state)

        verify { jdbcStmt.execute("PRAGMA foreign_keys = 1;") }
    }

    test("executeOrApply with plain SQL delegates to jdbcStmt.execute and returns false") {
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val state = RunnerHookHandler.State()

        val wasHook = RunnerHookHandler.executeOrApply(
            jdbcStmt = jdbcStmt,
            sql = "CREATE TABLE users (id INTEGER PRIMARY KEY);",
            state = state,
        )

        wasHook shouldBe false
        verify { jdbcStmt.execute("CREATE TABLE users (id INTEGER PRIMARY KEY);") }
        state.savedSqliteForeignKeysPragma shouldBe null
    }

    test("executeOrApply with hook marker returns true (consumed)") {
        val jdbcStmt = mockk<Statement>(relaxed = true)
        val state = RunnerHookHandler.State(savedSqliteForeignKeysPragma = 0)

        val wasHook = RunnerHookHandler.executeOrApply(
            jdbcStmt = jdbcStmt,
            sql = "-- dmigrate:runner-hook=restore-fk-state",
            state = state,
        )

        wasHook shouldBe true
        verify { jdbcStmt.execute("PRAGMA foreign_keys = 0;") }
    }

    test("apply assert-foreign-keys-clean: empty pragma_foreign_key_check passes silently") {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ON;")
                stmt.execute("CREATE TABLE t (id INTEGER PRIMARY KEY);")
                // No FK violations exist; the assert must not throw.
                RunnerHookHandler.apply(stmt, "assert-foreign-keys-clean", RunnerHookHandler.State())
            }
        } finally {
            conn.close()
        }
    }

    test("apply assert-foreign-keys-clean: non-empty result throws SQLException") {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { stmt ->
                // Create FK violation: parent table is missing.
                stmt.execute("PRAGMA foreign_keys = OFF;") // allow violation insert
                stmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY);")
                stmt.execute(
                    "CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER " +
                        "REFERENCES parent(id));",
                )
                stmt.execute("INSERT INTO child(id, parent_id) VALUES (1, 999);")
                // Now PRAGMA foreign_key_check reports the violation.
                stmt.execute("PRAGMA foreign_keys = ON;")
                try {
                    RunnerHookHandler.apply(stmt, "assert-foreign-keys-clean", RunnerHookHandler.State())
                    throw AssertionError("expected SQLException for FK violation")
                } catch (e: java.sql.SQLException) {
                    (e.message ?: "").contains("violation") shouldBe true
                    (e.message ?: "").contains("child") shouldBe true
                }
            }
        } finally {
            conn.close()
        }
    }

    test("end-to-end with embedded SQLite: prior OFF state is restored to OFF") {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF;") }

            val state = RunnerHookHandler.State()
            conn.createStatement().use { stmt ->
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=save-fk-state-before-pragma-off", state,
                )
                stmt.execute("PRAGMA foreign_keys = OFF;")
                stmt.execute("CREATE TABLE u (id INTEGER PRIMARY KEY);")
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=restore-fk-state", state,
                )
            }

            state.savedSqliteForeignKeysPragma shouldBe 0
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

    test("end-to-end with embedded SQLite: prior ON state remains ON") {
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON;") }

            val state = RunnerHookHandler.State()
            conn.createStatement().use { stmt ->
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=save-fk-state-before-pragma-off", state,
                )
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=restore-fk-state", state,
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

    test("H.3b rollback-path — prior FK=ON is restored to ON AFTER ROLLBACK when stream throws mid-rebuild") {
        // Plan-Doc-Vertrag §6.4 L2305: "Restore nach Commit/Rollback".
        // SQLite ignores `PRAGMA foreign_keys = ...` inside an open
        // transaction, so the restore MUST run AFTER `ROLLBACK;` —
        // otherwise the rebuild's intermediate `PRAGMA foreign_keys
        // = OFF` leaks past the rollback and the next migration runs
        // with FK enforcement silently disabled.
        //
        // This test drives the actual production [JdbcMigrationExecutor.runAll]
        // against an embedded SQLite connection: stream is classified
        // as stream-owned-tx (contains `BEGIN IMMEDIATE`), save-hook
        // captures the prior FK=ON, PRAGMA OFF + a good CREATE run,
        // then an invalid CREATE TABLE explodes. The catch path must
        // first send `ROLLBACK;` and then re-apply
        // `PRAGMA foreign_keys = 1`.
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON;") }

            val statements = listOf(
                ddl("BEGIN IMMEDIATE;"),
                ddl("-- dmigrate:runner-hook=save-fk-state-before-pragma-off"),
                ddl("PRAGMA foreign_keys = OFF;"),
                ddl("CREATE TABLE good (id INTEGER PRIMARY KEY);"),
                // Syntax-Error → SQLException → catch-Pfad → ROLLBACK; → Restore.
                ddl("CREATE TABLE BAD SQL THAT WILL THROW;"),
                ddl("COMMIT;"),
            )

            val trace = JdbcMigrationExecutor.runAll(conn, statements)

            trace.executionStarted shouldBe true
            trace.transactionRolledBack shouldBe true
            trace.sideEffectsPossible shouldBe false
            // FK must be back to the captured ON (1). If the restore
            // had run before ROLLBACK; the PRAGMA would have been a
            // no-op inside the open tx — assertion would observe 0.
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA foreign_keys;").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
            // And the good CREATE was rolled back — no leaked side-effect.
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='good';",
                ).use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 0
                }
            }
        } finally {
            conn.close()
        }
    }

    test("H.3b rollback-path — prior FK=OFF is restored to OFF AFTER ROLLBACK") {
        // Symmetric to the OFF→ON case: an explicit prior OFF state must
        // be re-asserted after rollback, not implicitly defaulted to ON.
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF;") }

            val statements = listOf(
                ddl("BEGIN IMMEDIATE;"),
                ddl("-- dmigrate:runner-hook=save-fk-state-before-pragma-off"),
                ddl("PRAGMA foreign_keys = OFF;"),
                ddl("CREATE TABLE BAD SQL;"),
                ddl("COMMIT;"),
            )

            val trace = JdbcMigrationExecutor.runAll(conn, statements)

            trace.transactionRolledBack shouldBe true
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

    test("H.3b rollback-path — no save-hook fired → no PRAGMA emission on rollback (no-op restore)") {
        // Defensive: if a stream is stream-owned but never saved an FK
        // state (rebuild not engaged), the rollback path must not
        // accidentally emit `PRAGMA foreign_keys = 1` and clobber the
        // session's existing OFF setting.
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF;") }

            val statements = listOf(
                ddl("BEGIN IMMEDIATE;"),
                ddl("CREATE TABLE BAD SQL;"),
                ddl("COMMIT;"),
            )

            val trace = JdbcMigrationExecutor.runAll(conn, statements)

            trace.transactionRolledBack shouldBe true
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

    test("W1 — two rebuilds in one stream: prior state is preserved across both save/restore cycles") {
        // The same per-stream State is shared by multiple rebuilds.
        // After Rebuild #1 (save → OFF → restore=orig), the state's
        // savedSqliteForeignKeysPragma is still pointing at the prior
        // orig value (restore didn't clear it). Rebuild #2's save then
        // captures the current state (= orig, since restore put it back),
        // and its restore returns again to orig. End-state: prior
        // preserved across both cycles.
        val conn = java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")
        try {
            // Initial state: OFF.
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = OFF;") }

            val state = RunnerHookHandler.State()
            conn.createStatement().use { stmt ->
                // Rebuild #1
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=save-fk-state-before-pragma-off", state,
                )
                stmt.execute("PRAGMA foreign_keys = OFF;")
                stmt.execute("CREATE TABLE a (id INTEGER PRIMARY KEY);")
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=restore-fk-state", state,
                )
                // Rebuild #2 — state is shared
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=save-fk-state-before-pragma-off", state,
                )
                stmt.execute("PRAGMA foreign_keys = OFF;")
                stmt.execute("CREATE TABLE b (id INTEGER PRIMARY KEY);")
                RunnerHookHandler.executeOrApply(
                    stmt, "-- dmigrate:runner-hook=restore-fk-state", state,
                )
            }

            // Both rebuilds saw OFF as the prior state.
            state.savedSqliteForeignKeysPragma shouldBe 0
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
})
