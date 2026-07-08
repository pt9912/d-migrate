package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement

class JdbcMigrationStatementExecutorTest : FunSpec({

    fun openSqlite(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    fun stmt(
        sql: String,
        id: String = "op",
        scope: TransactionScope = TransactionScope.RUNNER_OWNED,
    ): MigrationDdlStatement =
        MigrationDdlStatement(
            sql = sql,
            operationIds = setOf(id),
            risk = OperationRisk.SAFE,
            phase = DiffPhase.TABLES,
            transactionScope = scope,
        )

    fun pragmaForeignKeys(conn: Connection): Int =
        conn.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA foreign_keys;").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    test("execute returns completed trace without borrowing for empty statement list") {
        var borrowed = false
        val pool = object : ConnectionPool {
            override val dialect = DatabaseDialect.SQLITE
            override fun borrow(): DatabaseConnection {
                borrowed = true
                error("empty execution must not borrow")
            }
            override fun activeConnections(): Int = 0
            override fun close() {}
        }

        val trace = JdbcMigrationStatementExecutor.execute(pool, emptyList())

        borrowed shouldBe false
        trace.executionStarted shouldBe true
        trace.executionCompleted shouldBe true
        trace.statementsAttempted shouldBe 0
    }

    test("execute borrows neutral handle and closes underlying JDBC connection") {
        val conn = openSqlite()
        val pool = object : ConnectionPool {
            override val dialect = DatabaseDialect.SQLITE
            override fun borrow(): DatabaseConnection = JdbcDatabaseConnection(conn)
            override fun activeConnections(): Int = if (conn.isClosed) 0 else 1
            override fun close() {}
        }

        val trace = JdbcMigrationStatementExecutor.execute(
            pool,
            listOf(stmt("CREATE TABLE t (id INTEGER PRIMARY KEY)", id = "create-t")),
        )

        trace.executionCompleted shouldBe true
        trace.statementsAttempted shouldBe 1
        conn.isClosed shouldBe true
    }

    test("runner-owned transaction commits successful statements") {
        openSqlite().use { conn ->
            val trace = JdbcMigrationStatementExecutor.runAll(
                conn,
                listOf(
                    stmt("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)", id = "create-t"),
                    stmt("INSERT INTO t (id, name) VALUES (1, 'alice')", id = "insert-alice"),
                ),
            )

            trace.executionStarted shouldBe true
            trace.executionCompleted shouldBe true
            trace.statementsAttempted shouldBe 2
            trace.lastStatementOperationIds shouldBe setOf("insert-alice")
            trace.transactionRolledBack shouldBe false
            conn.createStatement().use { jdbcStmt ->
                jdbcStmt.executeQuery("SELECT name FROM t WHERE id = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldBe "alice"
                }
            }
        }
    }

    test("runner-owned transaction reports rollback on SQL failure") {
        openSqlite().use { conn ->
            val trace = JdbcMigrationStatementExecutor.runAll(
                conn,
                listOf(
                    stmt("CREATE TABLE t (id INTEGER PRIMARY KEY)", id = "create-t"),
                    stmt("INSERT INTO missing_table VALUES (1)", id = "bad-insert"),
                ),
            )

            trace.executionStarted shouldBe true
            trace.executionCompleted shouldBe true
            trace.statementsAttempted shouldBe 2
            trace.lastStatementOperationIds shouldBe setOf("bad-insert")
            trace.transactionRolledBack shouldBe true
            trace.sideEffectsPossible shouldBe false
            trace.executionError shouldContain "missing_table"
        }
    }

    test("stream-owned transaction applies hooks and restores foreign key pragma after rollback") {
        openSqlite().use { conn ->
            conn.createStatement().use { it.execute("PRAGMA foreign_keys = ON;") }
            pragmaForeignKeys(conn) shouldBe 1

            val trace = JdbcMigrationStatementExecutor.runAll(
                conn,
                listOf(
                    stmt("BEGIN IMMEDIATE;", id = "begin", scope = TransactionScope.STREAM_OWNED),
                    stmt(
                        "-- dmigrate:runner-hook=save-fk-state-before-pragma-off",
                        id = "save",
                        scope = TransactionScope.STREAM_OWNED,
                    ),
                    stmt("SELECT * FROM missing_table;", id = "bad-select", scope = TransactionScope.STREAM_OWNED),
                ),
            )

            trace.statementsAttempted shouldBe 3
            trace.lastStatementOperationIds shouldBe setOf("bad-select")
            trace.transactionRolledBack shouldBe true
            trace.sideEffectsPossible shouldBe false
            trace.executionError shouldContain "missing_table"
            pragmaForeignKeys(conn) shouldBe 1
        }
    }

    test("stream-owned rollback failure reports possible side effects") {
        openSqlite().use { conn ->
            val trace = JdbcMigrationStatementExecutor.runAll(
                conn,
                listOf(stmt("SELECT * FROM missing_table;", id = "bad-select", scope = TransactionScope.STREAM_OWNED)),
            )

            trace.statementsAttempted shouldBe 1
            trace.transactionRolledBack shouldBe false
            trace.sideEffectsPossible shouldBe true
            trace.executionError shouldContain "missing_table"
        }
    }

    test("runner hooks parse allowlisted markers only") {
        JdbcRunnerHookHandler.parseHook("-- dmigrate:runner-hook=restore-fk-state") shouldBe "restore-fk-state"
        JdbcRunnerHookHandler.parseHook("   -- dmigrate:runner-hook=restore-fk-state") shouldBe "restore-fk-state"
        JdbcRunnerHookHandler.parseHook("-- dmigrate:runner-hook=nonsense") shouldBe null
        JdbcRunnerHookHandler.parseHook("SELECT 1") shouldBe null
    }

    test("runner hooks execute non-hook SQL and apply restore hook") {
        openSqlite().use { conn ->
            val state = JdbcRunnerHookHandler.State(savedSqliteForeignKeysPragma = 0)
            conn.createStatement().use { jdbcStmt ->
                JdbcRunnerHookHandler.executeOrApply(jdbcStmt, "PRAGMA foreign_keys = ON;", state) shouldBe false
                pragmaForeignKeys(conn) shouldBe 1

                JdbcRunnerHookHandler.executeOrApply(
                    jdbcStmt,
                    "-- dmigrate:runner-hook=restore-fk-state",
                    state,
                ) shouldBe true
                pragmaForeignKeys(conn) shouldBe 0
            }
        }
    }

    test("foreign key check hook throws with sampled violations") {
        openSqlite().use { conn ->
            conn.createStatement().use { jdbcStmt ->
                jdbcStmt.execute("PRAGMA foreign_keys = OFF;")
                jdbcStmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                jdbcStmt.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))")
                jdbcStmt.execute("INSERT INTO child (id, parent_id) VALUES (1, 999)")

                val ex = shouldThrow<SQLException> {
                    JdbcRunnerHookHandler.apply(
                        jdbcStmt,
                        "assert-foreign-keys-clean",
                        JdbcRunnerHookHandler.State(),
                    )
                }

                ex.message shouldContain "PRAGMA foreign_key_check reported"
                ex.message shouldContain "table=child"
            }
        }
    }

    test("foreign key check hook passes for clean schema") {
        openSqlite().use { conn ->
            conn.createStatement().use { jdbcStmt ->
                jdbcStmt.execute("PRAGMA foreign_keys = ON;")
                jdbcStmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                jdbcStmt.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))")
                jdbcStmt.execute("INSERT INTO parent (id) VALUES (1)")
                jdbcStmt.execute("INSERT INTO child (id, parent_id) VALUES (1, 1)")

                JdbcRunnerHookHandler.apply(
                    jdbcStmt,
                    "assert-foreign-keys-clean",
                    JdbcRunnerHookHandler.State(),
                )
            }
        }
    }

    test("unknown hook passed directly to apply is rejected") {
        openSqlite().use { conn ->
            conn.createStatement().use { jdbcStmt ->
                shouldThrow<IllegalStateException> {
                    JdbcRunnerHookHandler.apply(jdbcStmt, "unknown", JdbcRunnerHookHandler.State())
                }.message shouldContain "unrecognised hook"
            }
        }
    }

    test("save hook records zero when pragma query has no row") {
        val statement = java.lang.reflect.Proxy.newProxyInstance(
            Statement::class.java.classLoader,
            arrayOf(Statement::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "executeQuery" -> java.lang.reflect.Proxy.newProxyInstance(
                    java.sql.ResultSet::class.java.classLoader,
                    arrayOf(java.sql.ResultSet::class.java),
                ) { _, rsMethod, _ ->
                    when (rsMethod.name) {
                        "next" -> false
                        "close" -> Unit
                        else -> defaultValue(rsMethod.returnType)
                    }
                }
                "close" -> Unit
                else -> defaultValue(method.returnType)
            }
        } as java.sql.Statement

        val state = JdbcRunnerHookHandler.State()
        JdbcRunnerHookHandler.apply(statement, "save-fk-state-before-pragma-off", state)
        state.savedSqliteForeignKeysPragma shouldBe 0
    }

    test("foreign key check hook samples at most fifty violations") {
        openSqlite().use { conn ->
            conn.createStatement().use { jdbcStmt ->
                jdbcStmt.execute("PRAGMA foreign_keys = OFF;")
                jdbcStmt.execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
                jdbcStmt.execute("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))")
                repeat(55) { i ->
                    jdbcStmt.execute("INSERT INTO child (id, parent_id) VALUES (${i + 1}, 999)")
                }

                val ex = shouldThrow<SQLException> {
                    JdbcRunnerHookHandler.apply(
                        jdbcStmt,
                        "assert-foreign-keys-clean",
                        JdbcRunnerHookHandler.State(),
                    )
                }

                ex.message shouldContain "50 violation(s)"
                ex.message!!.split("table=child").size shouldBe 51
            }
        }
    }
})

private fun defaultValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Void.TYPE -> Unit
        else -> null
    }
