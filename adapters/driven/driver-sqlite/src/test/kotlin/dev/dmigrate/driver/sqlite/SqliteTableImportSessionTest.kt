package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Statement

class SqliteTableImportSessionTest : FunSpec({

    test("close aborts connection when fk reset retry fails after finishTable partial failure") {
        var autoCommit = false
        var abortCalled = false
        val firstResetFailure = RuntimeException("first fk reset failed")
        val secondResetFailure = RuntimeException("second fk reset failed")
        var resetAttempts = 0
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    autoCommit = args?.get(0) as Boolean
                    null
                }
                "createStatement" -> failingSqliteStatement {
                    resetAttempts++
                    if (resetAttempts == 1) firstResetFailure else secondResetFailure
                }
                "rollback", "close" -> null
                "abort" -> {
                    abortCalled = true
                    null
                }
                "isClosed" -> false
                "unwrap" -> null
                "isWrapperFor" -> false
                "toString" -> "FakeSqliteConnection"
                "hashCode" -> 0
                "equals" -> false
                else -> error("Unexpected Connection method in test: ${method.name}")
            }
        } as Connection

        val session = SqliteTableImportSession(
            conn = connection,
            savedAutoCommit = false,
            table = "writer_child",
            qualifiedTable = SqliteQualifiedTableName(null, "writer_child"),
            targetColumns = listOf(TargetColumn("id", nullable = false, jdbcType = java.sql.Types.INTEGER)),
            primaryKeyColumns = emptyList(),
            options = ImportOptions(disableFkChecks = true, reseedSequences = false),
            schemaSync = SqliteSchemaSync(),
            fkChecksDisabled = true,
        )

        val finish = session.finishTable()
        finish shouldBe FinishTableResult.PartialFailure(emptyList(), firstResetFailure)
        session.close()

        abortCalled shouldBe true
        firstResetFailure.suppressedExceptions.shouldHaveSize(1)
        firstResetFailure.suppressedExceptions.single().message shouldBe "second fk reset failed"
    }
})

private fun failingSqliteStatement(nextResetFailure: () -> RuntimeException): Statement =
    Proxy.newProxyInstance(
        Statement::class.java.classLoader,
        arrayOf(Statement::class.java),
    ) { _, method, args ->
        when (method.name) {
            "execute" -> {
                val sql = args?.get(0) as String
                if (sql == "PRAGMA foreign_keys = ON") {
                    throw nextResetFailure()
                }
                true
            }
            "close" -> null
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeSqliteStatement"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Statement method in test: ${method.name}")
        }
    } as Statement
