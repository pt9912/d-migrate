package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S9b Single-File-Test-Familie **1 (CLI-Preflight-Codes)**: fährt
 * [DataImportRunner.execute] end-to-end mit dem echten
 * [ParquetImportInputResolutionHook] gegen den in S9b-0 hergestellten
 * AP12-§9-Exit-Code-Vertrag (`PARQUET_SINGLE_FILE_TABLE_*` → Exit 4).
 * Plus den Stdin-Usage-Reject (Exit 2, primär in
 * `validateFormatPathRequirements`, vor dem Hook).
 *
 * Single-File-Resume-Codes (`CONTENT_CHANGED_SINCE_CHECKPOINT` → Exit 3,
 * Resume-Familie/Manager) sind S9b.3.
 */
class DataImportRunnerParquetSingleFilePreflightTest : FunSpec({

    class FakeConnectionPool(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : ConnectionPool {
        override fun borrow(): Connection = error("borrow() must not be called — preflight fails first")
        override fun activeConnections(): Int = 0
        override fun close() {}
    }

    class FakeDataWriter(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : DataWriter {
        override fun schemaSync(): SchemaSync = error("not used")
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession =
            error("not used")
    }

    fun newRunner(stderr: (String) -> Unit): DataImportRunner = DataImportRunner(
        targetResolver = { t, _ -> t ?: error("no target") },
        urlParser = {
            ConnectionConfig(DatabaseDialect.SQLITE, null, null, "/tmp/x.db", null, null)
        },
        poolFactory = { FakeConnectionPool() },
        writerLookup = { FakeDataWriter() },
        importExecutor = { _, _, _, _ -> error("executor must not run — preflight fails first") },
        stderr = stderr,
        inputResolutionHook = ParquetImportInputResolutionHook(),
    )

    fun request(source: String, table: String?) = DataImportRequest(
        target = "sqlite:///tmp/x.db",
        source = source,
        format = "parquet",
        schema = null,
        table = table,
        tables = null,
        onError = "abort",
        onConflict = null,
        triggerMode = "fire",
        truncate = false,
        disableFkChecks = false,
        reseedSequences = true,
        encoding = null,
        csvNoHeader = false,
        csvNullString = "",
        chunkSize = 10_000,
        cliConfigPath = null,
        quiet = true,
        noProgress = true,
    )

    fun writeSingleFile(path: Path, footerTable: String?) {
        val schema = ChunkSchema(
            table = footerTable ?: "ignored",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val out = Files.newOutputStream(path)
        val writer = if (footerTable != null) {
            val provider = ParquetSingleFileManifestWriter(
                producerVersion = "0.9.8",
                clock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC),
            ).provider
            ParquetChunkWriter(out, extraMetaDataProvider = provider)
        } else {
            ParquetChunkWriter(out)
        }
        writer.use { w ->
            w.begin(schema.table, schema)
            w.write(DataChunk(table = schema.table, columns = emptyList(), rows = listOf(arrayOf<Any?>(1L)), chunkIndex = 0L))
            w.end()
        }
    }

    test("CLI single-file import: kein --table und kein Footer-KV → Exit 4 (PARQUET_SINGLE_FILE_TABLE_REQUIRED)") {
        val file = Files.createTempFile("s9b1-table-required-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFile(file, footerTable = null)
            val lines = mutableListOf<String>()
            val code = newRunner(lines::add).execute(request(file.toString(), table = null))
            code shouldBe 4
            lines.joinToString("\n") shouldContain "PARQUET_SINGLE_FILE_TABLE_REQUIRED"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("CLI single-file import: --table widerspricht Footer-KV → Exit 4 (PARQUET_SINGLE_FILE_TABLE_MISMATCH)") {
        val file = Files.createTempFile("s9b1-table-mismatch-", ".parquet")
        Files.deleteIfExists(file)
        try {
            writeSingleFile(file, footerTable = "users")
            val lines = mutableListOf<String>()
            val code = newRunner(lines::add).execute(request(file.toString(), table = "orders"))
            code shouldBe 4
            lines.joinToString("\n") shouldContain "PARQUET_SINGLE_FILE_TABLE_MISMATCH"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("CLI single-file import: parquet über stdin → Exit 2 (Usage, vor dem Hook)") {
        val lines = mutableListOf<String>()
        val code = newRunner(lines::add).execute(request("-", table = "users"))
        code shouldBe 2
    }
})
