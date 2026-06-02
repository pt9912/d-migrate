package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027: Cancel inside the transfer pipeline must surface as
 * exit 130 — never as the generic 4 (schema read), 3 (schema mismatch)
 * or 5 (transfer error) paths. LF-012 / LN-011 / LN-017 / LN-027, §6.4.
 */
class DataTransferRunnerCancelCheckpointTest : FunSpec({

    val fakeSchema = SchemaDefinition(
        name = "test", version = "1.0",
        tables = mapOf(
            "users" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true))),
                primaryKey = listOf("id"),
            ),
        ),
    )
    val fakeCfg = ConnectionConfig(DatabaseDialect.SQLITE, "h", null, "d", null, null)
    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }
    val fakeReader = object : DataReader {
        override val dialect = DatabaseDialect.SQLITE
        override fun streamTable(
            pool: ConnectionPool, table: String,
            filter: dev.dmigrate.core.data.DataFilter?, chunkSize: Int,
        ) = error("not invoked when executor is short-circuited")
    }
    val fakeWriter = object : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) =
            error("not invoked")
    }
    val fakeSchemaReader = object : SchemaReader {
        override fun read(pool: ConnectionPool, options: SchemaReadOptions) = SchemaReadResult(schema = fakeSchema)
    }
    val fakeDriver = object : DatabaseDriver {
        override val dialect = DatabaseDialect.SQLITE
        override fun ddlGenerator() = throw UnsupportedOperationException()
        override fun dataReader() = fakeReader
        override fun tableLister() = throw UnsupportedOperationException()
        override fun dataWriter() = fakeWriter
        override fun urlBuilder() = throw UnsupportedOperationException()
        override fun schemaReader() = fakeSchemaReader
    }

    fun buildRunner(executor: TransferExecutor) = DataTransferRunner(
        sourceResolver = { s, _ -> s },
        targetResolver = { s, _ -> s },
        urlParser = { fakeCfg },
        poolFactory = { fakePool },
        driverLookup = { fakeDriver },
        printError = { _, _ -> },
        stderr = { },
        transferExecutor = executor,
    )

    fun request() = DataTransferRequest(
        source = "sqlite:///src.db", target = "sqlite:///tgt.db",
        tables = listOf("users"), quiet = true, noProgress = true,
    )

    test("OperationCancelledException from executor maps to exit 130, not 5") {
        val executor = object : TransferExecutor() {
            override fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
                throw OperationCancelledException("worker observed cancel")
            }
        }
        val runner = buildRunner(executor)
        val token = CancellationTokenSource.create().token

        runner.execute(request(), token) shouldBe DataTransferRunner.CANCELLED_EXIT_CODE
    }

    test("Cancel before connection-resolve returns 130 without invoking executor") {
        val executorInvoked = AtomicInteger(0)
        val executor = object : TransferExecutor() {
            override fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
                executorInvoked.incrementAndGet()
                context.tables.forEach(onTableTransferred)
            }
        }
        val runner = buildRunner(executor)
        val source = CancellationTokenSource.create().also { it.cancel("before-pool") }

        runner.execute(request(), source.token) shouldBe DataTransferRunner.CANCELLED_EXIT_CODE
        executorInvoked.get() shouldBe 0
    }

    test("Cancel raised during executor still maps to 130 (not 5)") {
        // Simulate cancel mid-transfer: executor throws OperationCancelledException
        // wrapped in nothing — transfer-error catch-Exception must NOT swallow
        // it and produce 5. The explicit catch-OperationCancelledException
        // re-throws so the outer try maps to 130.
        val executor = object : TransferExecutor() {
            override fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
                throw OperationCancelledException("mid-transfer")
            }
        }
        val runner = buildRunner(executor)

        runner.execute(request(), CancellationTokenSource.create().token) shouldBe
            DataTransferRunner.CANCELLED_EXIT_CODE
    }

    test("ImportSchemaMismatchException keeps exit code 3 (regression guard)") {
        val executor = object : TransferExecutor() {
            override fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
                throw ImportSchemaMismatchException("col drift")
            }
        }
        val runner = buildRunner(executor)

        runner.execute(request()) shouldBe 3
    }

    test("Default token completes transfer with exit 0") {
        val executor = object : TransferExecutor() {
            override fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
                context.tables.forEach(onTableTransferred)
            }
        }
        val runner = buildRunner(executor)

        runner.execute(request()) shouldBe 0
    }
})
