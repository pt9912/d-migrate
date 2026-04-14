package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.sql.Connection

class DataTransferRunnerTest : FunSpec({

    val fakeSchema = SchemaDefinition(
        name = "test", version = "1.0",
        tables = mapOf(
            "users" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                    "name" to ColumnDefinition(type = NeutralType.Text(100)),
                ),
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
    class EmptyChunkSequence : ChunkSequence {
        override fun iterator(): Iterator<DataChunk> = emptyList<DataChunk>().iterator()
        override fun close() {}
    }
    val fakeSession = object : TableImportSession {
        override val targetColumns = listOf(
            TargetColumn("id", false, java.sql.Types.INTEGER),
            TargetColumn("name", true, java.sql.Types.VARCHAR),
        )
        override fun write(chunk: DataChunk) = WriteResult(chunk.rows.size.toLong(), 0, 0)
        override fun commitChunk() {}
        override fun rollbackChunk() {}
        override fun markTruncatePerformed() {}
        override fun finishTable() = FinishTableResult.Success(emptyList())
        override fun close() {}
    }
    val fakeReader = object : DataReader {
        override val dialect = DatabaseDialect.SQLITE
        override fun streamTable(pool: ConnectionPool, table: String, filter: dev.dmigrate.core.data.DataFilter?, chunkSize: Int) = EmptyChunkSequence()
    }
    val fakeWriter = object : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) = fakeSession
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

    class Capture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined() = lines.joinToString("\n")
    }

    fun buildRunner(
        errors: Capture = Capture(), stderr: Capture = Capture(),
        sourceResolver: (String, Path?) -> String = { s, _ -> s },
        targetResolver: (String, Path?) -> String = { s, _ -> s },
        poolFactory: (ConnectionConfig) -> ConnectionPool = { fakePool },
        driverLookup: (DatabaseDialect) -> DatabaseDriver = { fakeDriver },
    ) = Triple(
        DataTransferRunner(
            sourceResolver = sourceResolver, targetResolver = targetResolver,
            urlParser = { fakeCfg }, poolFactory = poolFactory,
            driverLookup = driverLookup,
            urlScrubber = { url -> url.replace(Regex(":[^@/]+@"), ":***@") },
            printError = { msg, src -> errors.sink("[$src] $msg") },
            stderr = stderr.sink,
        ), stderr, errors,
    )

    fun request(
        source: String = "sqlite:///src.db", target: String = "sqlite:///tgt.db",
        tables: List<String>? = null, onConflict: String = "abort",
        triggerMode: String = "fire", quiet: Boolean = false, noProgress: Boolean = false,
        sinceColumn: String? = null, since: String? = null, filter: String? = null,
    ) = DataTransferRequest(source = source, target = target, tables = tables,
        onConflict = onConflict, triggerMode = triggerMode, quiet = quiet,
        noProgress = noProgress, sinceColumn = sinceColumn, since = since, filter = filter)

    // ── Exit 0 ──────────────────────────────────

    test("successful transfer returns exit 0") {
        val (runner, stderr, _) = buildRunner()
        runner.execute(request()) shouldBe 0
        stderr.joined() shouldContain "Transfer complete"
    }

    test("quiet suppresses all output") {
        val (runner, stderr, _) = buildRunner()
        runner.execute(request(quiet = true)) shouldBe 0
        stderr.lines.size shouldBe 0
    }

    test("noProgress suppresses progress") {
        val (runner, stderr, _) = buildRunner()
        runner.execute(request(noProgress = true)) shouldBe 0
        stderr.lines.size shouldBe 0
    }

    // ── Exit 2 ──────────────────────────────────

    test("since without sinceColumn → exit 2") {
        val (runner, _, errors) = buildRunner()
        runner.execute(request(since = "2024-01-01")) shouldBe 2
    }

    test("sinceColumn without since → exit 2") {
        val (runner, _, _) = buildRunner()
        runner.execute(request(sinceColumn = "ts")) shouldBe 2
    }

    test("filter with ? and since → exit 2 (M-R5)") {
        val (runner, _, _) = buildRunner()
        runner.execute(request(filter = "x = ?", sinceColumn = "ts", since = "v")) shouldBe 2
    }

    test("unknown trigger mode → exit 2") {
        val (runner, _, _) = buildRunner()
        runner.execute(request(triggerMode = "invalid")) shouldBe 2
    }

    // ── Exit 3 ──────────────────────────────────

    test("missing source table → exit 3") {
        val (runner, _, errors) = buildRunner()
        runner.execute(request(tables = listOf("nonexistent"))) shouldBe 3
        errors.joined() shouldContain "not found"
    }

    test("update without PK → exit 3") {
        val noPkSchema = SchemaDefinition(name = "t", version = "1.0",
            tables = mapOf("t" to TableDefinition(
                columns = mapOf("a" to ColumnDefinition(type = NeutralType.Text())))))
        val noPkReader = object : SchemaReader {
            override fun read(pool: ConnectionPool, options: SchemaReadOptions) = SchemaReadResult(schema = noPkSchema)
        }
        val drv = object : DatabaseDriver {
            override val dialect = DatabaseDialect.SQLITE
            override fun ddlGenerator() = throw UnsupportedOperationException()
            override fun dataReader() = fakeReader
            override fun tableLister() = throw UnsupportedOperationException()
            override fun dataWriter() = fakeWriter
            override fun urlBuilder() = throw UnsupportedOperationException()
            override fun schemaReader() = noPkReader
        }
        val (runner, _, _) = buildRunner(driverLookup = { drv })
        runner.execute(request(onConflict = "update")) shouldBe 3
    }

    test("writer schema mismatch from openTable → exit 3") {
        val mismatchWriter = object : DataWriter {
            override val dialect = DatabaseDialect.SQLITE
            override fun schemaSync() = throw UnsupportedOperationException()
            override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession {
                throw dev.dmigrate.core.data.ImportSchemaMismatchException("Column 'x' not found in target")
            }
        }
        val drv = object : DatabaseDriver {
            override val dialect = DatabaseDialect.SQLITE
            override fun ddlGenerator() = throw UnsupportedOperationException()
            override fun dataReader() = fakeReader
            override fun tableLister() = throw UnsupportedOperationException()
            override fun dataWriter() = mismatchWriter
            override fun urlBuilder() = throw UnsupportedOperationException()
            override fun schemaReader() = fakeSchemaReader
        }
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors, driverLookup = { drv })
        runner.execute(request()) shouldBe 3
        errors.joined() shouldContain "Schema mismatch"
    }

    // ── Exit 4 ──────────────────────────────────

    test("pool failure → exit 4") {
        val (runner, _, _) = buildRunner(poolFactory = { throw RuntimeException("refused") })
        runner.execute(request()) shouldBe 4
    }

    // ── Exit 7 ──────────────────────────────────

    test("source resolution failure → exit 7") {
        val (runner, _, _) = buildRunner(sourceResolver = { _, _ -> throw RuntimeException("fail") })
        runner.execute(request()) shouldBe 7
    }

    test("target resolution failure → exit 7") {
        val (runner, _, _) = buildRunner(targetResolver = { _, _ -> throw RuntimeException("fail") })
        runner.execute(request()) shouldBe 7
    }

    // ── Scrubbing ───────────────────────────────

    test("credentials scrubbed in flag validation errors (exit 2)") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors)
        runner.execute(request(
            source = "postgresql://admin:secret@host/db",
            triggerMode = "invalid",
        )) shouldBe 2
        errors.joined() shouldNotContain "secret"
    }

    test("credentials scrubbed in source resolve errors (exit 7)") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors,
            sourceResolver = { _, _ -> throw RuntimeException("fail") })
        runner.execute(request(source = "postgresql://admin:secret@host/db")) shouldBe 7
        errors.joined() shouldNotContain "secret"
    }

    test("credentials scrubbed in target resolve errors (exit 7)") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors,
            targetResolver = { _, _ -> throw RuntimeException("fail") })
        runner.execute(request(target = "postgresql://admin:secret@host/db")) shouldBe 7
        errors.joined() shouldNotContain "secret"
    }

    test("credentials scrubbed in pool errors (exit 4)") {
        val errors = Capture()
        val (runner, _, _) = buildRunner(errors = errors,
            poolFactory = { throw RuntimeException("postgresql://a:secret@h/d") })
        runner.execute(request(source = "postgresql://a:secret@h/d")) shouldBe 4
        errors.joined() shouldNotContain "secret"
        errors.joined() shouldContain "***"
    }
})
