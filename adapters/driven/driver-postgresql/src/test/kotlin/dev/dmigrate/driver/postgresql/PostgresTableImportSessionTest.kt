package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.lang.reflect.Proxy
import java.sql.Connection

class PostgresTableImportSessionTest : FunSpec({

    test("close keeps trigger re-enable retry as suppressed on partial failure cause") {
        val firstEnableFailure = RuntimeException("first enable failed")
        val secondEnableFailure = RuntimeException("second enable failed")
        val schemaSync = object : SchemaSync {
            var enableCalls = 0

            override fun reseedGenerators(
                conn: Connection,
                table: String,
                importedColumns: List<dev.dmigrate.core.data.ColumnDescriptor>,
            ) = emptyList<dev.dmigrate.driver.data.SequenceAdjustment>()

            override fun disableTriggers(conn: Connection, table: String) = Unit

            override fun assertNoUserTriggers(conn: Connection, table: String) = Unit

            override fun enableTriggers(conn: Connection, table: String) {
                enableCalls++
                throw if (enableCalls == 1) firstEnableFailure else secondEnableFailure
            }
        }

        val session = PostgresTableImportSession(
            conn = fakeConnection(),
            savedAutoCommit = true,
            table = "public.t",
            qualifiedTable = QualifiedTableName("public", "t"),
            targetColumns = emptyList(),
            generatedAlwaysColumns = emptySet(),
            primaryKeyColumns = emptyList(),
            options = ImportOptions(),
            schemaSync = schemaSync,
            triggersDisabled = true,
        )

        val finish = session.finishTable()
        finish shouldBe FinishTableResult.PartialFailure(emptyList(), firstEnableFailure)

        session.close()

        firstEnableFailure.suppressedExceptions.shouldHaveSize(1)
        firstEnableFailure.suppressedExceptions.single().message shouldBe "second enable failed"
    }
})

private fun fakeConnection(): Connection {
    var autoCommit = true
    return Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getAutoCommit" -> autoCommit
            "setAutoCommit" -> {
                autoCommit = args?.get(0) as Boolean
                null
            }
            "close", "rollback" -> null
            "isClosed" -> false
            "unwrap" -> null
            "isWrapperFor" -> false
            "toString" -> "FakeConnection"
            "hashCode" -> 0
            "equals" -> false
            else -> error("Unexpected Connection method in test: ${method.name}")
        }
    } as Connection
}
