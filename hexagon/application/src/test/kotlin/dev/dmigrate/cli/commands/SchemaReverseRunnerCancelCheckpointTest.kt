package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.TestCancellationTokenSource
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase E0.4 checkpoint guard for the reverse pipeline. Cancel at any of the
 * three runner-level checkpoints must (a) prevent the next side effect and
 * (b) surface as exit 130 — never as the generic exit 4/7 path
 * (implementation-plan-0.9.6 §4.5, §6.1, §7.4).
 */
class SchemaReverseRunnerCancelCheckpointTest : FunSpec({

    val fakeSchema = SchemaDefinition(name = "rev:schema", version = "0.0.0")
    val fakeResult = SchemaReadResult(schema = fakeSchema)
    val fakeConfig = ConnectionConfig(DatabaseDialect.SQLITE, "h", null, "d", null, null)
    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    class CountingReader : SchemaReader {
        val calls = AtomicInteger(0)
        override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
            calls.incrementAndGet()
            return fakeResult
        }
    }

    fun driverFor(reader: CountingReader) = object : DatabaseDriver {
        override val dialect = DatabaseDialect.SQLITE
        override fun ddlGenerator() = throw UnsupportedOperationException()
        override fun dataReader() = throw UnsupportedOperationException()
        override fun tableLister() = throw UnsupportedOperationException()
        override fun dataWriter() = throw UnsupportedOperationException()
        override fun urlBuilder() = throw UnsupportedOperationException()
        override fun schemaReader() = reader
    }

    class CountingPath(val schema: AtomicInteger = AtomicInteger(0), val report: AtomicInteger = AtomicInteger(0))

    fun buildRunner(
        reader: CountingReader,
        counters: CountingPath,
    ) = SchemaReverseRunner(
        sourceResolver = { s, _ -> s },
        urlParser = { fakeConfig },
        poolFactory = { fakePool },
        driverLookup = { driverFor(reader) },
        schemaWriter = { _: Path, _: SchemaDefinition, _: String? -> counters.schema.incrementAndGet(); Unit },
        reportWriter = { _: Path, _: dev.dmigrate.driver.SchemaReadReportInput -> counters.report.incrementAndGet(); Unit },
        sidecarPath = { p, ext -> Path.of("$p$ext") },
        formatValidator = { _, _ -> },
        printError = { _, _ -> },
        stdout = { },
        stderr = { },
    )

    fun request() = SchemaReverseRequest(
        source = "sqlite:///x.db",
        output = Path.of("/tmp/out.yaml"),
        quiet = true,
    )

    test("cancel before introspection returns 130 and never reads the schema") {
        val reader = CountingReader()
        val counters = CountingPath()
        val runner = buildRunner(reader, counters)
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0, "before-introspect") }

        runner.execute(request(), source.token) shouldBe 130

        reader.calls.get() shouldBe 0
        counters.schema.get() shouldBe 0
        counters.report.get() shouldBe 0
    }

    test("cancel between introspection and schema publish returns 130 and writes nothing") {
        val reader = CountingReader()
        val counters = CountingPath()
        val runner = buildRunner(reader, counters)
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(1, "after-read") }

        runner.execute(request(), source.token) shouldBe 130

        reader.calls.get() shouldBe 1
        counters.schema.get() shouldBe 0
        counters.report.get() shouldBe 0
    }

    test("cancel between schema publish and report publish returns 130 with no report") {
        val reader = CountingReader()
        val counters = CountingPath()
        val runner = buildRunner(reader, counters)
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(2, "after-schema") }

        runner.execute(request(), source.token) shouldBe 130

        reader.calls.get() shouldBe 1
        counters.schema.get() shouldBe 1
        counters.report.get() shouldBe 0
    }

    test("default token leaves all stages running and returns 0") {
        val reader = CountingReader()
        val counters = CountingPath()
        val runner = buildRunner(reader, counters)

        runner.execute(request(), CancellationToken.none()) shouldBe 0

        reader.calls.get() shouldBe 1
        counters.schema.get() shouldBe 1
        counters.report.get() shouldBe 1
    }
})
