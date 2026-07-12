package dev.dmigrate.cli.commands

import dev.dmigrate.cli.commands.verify.ColumnExclusion
import dev.dmigrate.cli.commands.verify.TableVerifyResult
import dev.dmigrate.cli.commands.verify.VerifyReport
import dev.dmigrate.core.data.DataChunk
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
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.chunkSchemaOf
import dev.dmigrate.verify.ValueCanonicalizer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

/**
 * LN-009: Verify-Verdrahtung im Runner (--verify) + reportVerify-Branches.
 */
class DataTransferRunnerVerifyTest : FunSpec({

    val fakeSchema = SchemaDefinition(
        name = "t", version = "1.0",
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
        override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }
    val emptySequence = object : ChunkSequence {
        override val schema: ChunkSchema = chunkSchemaOf("users", emptyList())
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
        override fun streamTable(pool: ConnectionPool, table: String, filter: dev.dmigrate.core.data.DataFilter?, chunkSize: Int) =
            emptySequence
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

    val canon = ValueCanonicalizer { v, _ -> v.toString().toByteArray() }

    fun runner(errors: Capture, stderr: Capture, canonicalizer: ValueCanonicalizer? = canon) = DataTransferRunner(
        sourceResolver = { s, _ -> s }, targetResolver = { s, _ -> s },
        urlParser = { fakeCfg }, poolFactory = { fakePool },
        driverLookup = { fakeDriver },
        printError = { msg, src -> errors.sink("[$src] $msg") },
        stderr = stderr.sink,
        valueCanonicalizer = canonicalizer,
    )

    fun request(verify: Boolean = true, quiet: Boolean = false) =
        DataTransferRequest(source = "sqlite:///s.db", target = "sqlite:///t.db", verify = verify, quiet = quiet)

    // ── End-to-end wiring ────────────────────────

    test("--verify with empty tables → exit 0 + Verify OK") {
        val errors = Capture(); val stderr = Capture()
        runner(errors, stderr).execute(request(verify = true)) shouldBe 0
        stderr.joined() shouldContain "Verify OK"
    }

    test("--verify without injected canonicalizer → exit 7") {
        val errors = Capture(); val stderr = Capture()
        runner(errors, stderr, canonicalizer = null).execute(request(verify = true)) shouldBe 7
        errors.joined() shouldContain "requires a value canonicalizer"
    }

    // ── reportVerify branches ────────────────────

    fun report(vararg results: TableVerifyResult) = VerifyReport(results.toList())

    test("reportVerify: allMatch → 0 + Verify OK") {
        val errors = Capture(); val stderr = Capture()
        val r = runner(errors, stderr)
        r.reportVerify(request(), report(TableVerifyResult("users", 2, 2, "aa", "aa")), "s", "t") shouldBe 0
        stderr.joined() shouldContain "Verify OK"
    }

    test("reportVerify: quiet unterdrückt OK-Zeile, Code bleibt 0") {
        val errors = Capture(); val stderr = Capture()
        val r = runner(errors, stderr)
        r.reportVerify(request(quiet = true), report(TableVerifyResult("users", 1, 1, "h", "h")), "s", "t") shouldBe 0
        stderr.joined() shouldNotContain "Verify OK"
    }

    test("reportVerify: Ausschlüsse werden als W-Code gemeldet") {
        val errors = Capture(); val stderr = Capture()
        val r = runner(errors, stderr)
        val res = TableVerifyResult("users", 1, 1, "h", "h", excluded = listOf(ColumnExclusion("users", "amount", "float width")))
        r.reportVerify(request(), report(res), "s", "t") shouldBe 0
        stderr.joined() shouldContain "verify excluded users.amount"
    }

    test("reportVerify: Zeilenzahl-Divergenz → 3 + row count") {
        val errors = Capture(); val stderr = Capture()
        val r = runner(errors, stderr)
        r.reportVerify(request(), report(TableVerifyResult("users", 2, 1, "h1", "h2")), "s", "t") shouldBe 3
        errors.joined() shouldContain "row count 2 != 1"
    }

    test("reportVerify: checksum mismatch → 3") {
        val errors = Capture(); val stderr = Capture()
        val r = runner(errors, stderr)
        r.reportVerify(request(), report(TableVerifyResult("users", 1, 1, "h1", "h2")), "s", "t") shouldBe 3
        errors.joined() shouldContain "checksum mismatch"
    }

    test("reportVerify: inkonklusiv → 3 + Grund") {
        val errors = Capture(); val stderr = Capture()
        val r = runner(errors, stderr)
        r.reportVerify(request(), report(TableVerifyResult("users", 0, 0, "", "", error = "boom")), "s", "t") shouldBe 3
        errors.joined() shouldContain "inconclusive (boom)"
    }
})
