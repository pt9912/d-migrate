package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
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
import dev.dmigrate.driver.data.TargetColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/**
 * LF-012 / LN-011 / LN-017 / LN-027 propagation guard: a token passed to [DataTransferRunner.execute]
 * must reach the [TransferExecutor] via [TransferExecutionContext.cancellationToken].
 * LF-012 / LN-011 / LN-017 / LN-027 will use the same field at reader/writer boundaries.
 */
class DataTransferRunnerCancelPropagationTest : FunSpec({

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
        override fun streamTable(pool: ConnectionPool, table: String, filter: dev.dmigrate.core.data.DataFilter?, chunkSize: Int) =
            error("not invoked — capturing executor short-circuits before reader use")
    }
    val fakeWriter = object : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) =
            error("not invoked — capturing executor short-circuits before writer use")
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

    class CapturingExecutor : TransferExecutor() {
        val captured = AtomicReference<CancellationToken?>(null)
        override fun execute(context: TransferExecutionContext, onTableTransferred: (String) -> Unit) {
            captured.set(context.cancellationToken)
            // Don't iterate — just confirm propagation. Caller-supplied callback
            // is invoked once so the runner's quiet-output branch is exercised.
            context.tables.forEach(onTableTransferred)
        }
    }

    fun buildRunner(executor: TransferExecutor): DataTransferRunner = DataTransferRunner(
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
        tables = listOf("users"), quiet = true,
    )

    test("explicit cancellation token propagates into TransferExecutionContext") {
        val executor = CapturingExecutor()
        val runner = buildRunner(executor)
        val token = CancellationTokenSource.create().token

        runner.execute(request(), token) shouldBe 0

        (executor.captured.get() === token) shouldBe true
    }

    test("default cancellation token is none() when caller omits it") {
        val executor = CapturingExecutor()
        val runner = buildRunner(executor)

        runner.execute(request()) shouldBe 0

        executor.captured.get()!!.isCancellationRequested shouldBe false
    }
})
