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
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.checkpoint.FileCheckpointStore
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
 * S9b Single-File-Test-Familie **3 (Single-File-Resume)** auf CLI-Ebene,
 * Zwei-Phasen (analog S9a.3). Testet zugleich den S9b-Resume-Fix: der
 * Fresh-Run berechnet+persistiert den Content-Hash, wenn `--checkpoint-dir`
 * aktiv ist (vorher fiel jeder Single-File-Resume auf den Pre-AP8-Branch).
 *
 * Der Resume-Content-Check ist die **Manager**-Familie
 * (`validateSingleFileResume`, S8c → Exit 3), nicht der Hook (S8d-Re-Cut).
 */
class DataImportRunnerParquetSingleFileResumeTest : FunSpec({

    class FakeConnectionPool(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : ConnectionPool {
        override fun borrow(): Connection = error("borrow() must not be called")
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

    val successExecutor: ImportExecutor = ImportExecutor { ctx, _, _, _ ->
        val table = when (val input = ctx.input) {
            is dev.dmigrate.streaming.ImportInput.ResolvedSingleFile -> input.table
            else -> error("expected ResolvedSingleFile, got ${ctx.input}")
        }
        ImportResult(
            tables = listOf(
                TableImportSummary(
                    table = table, rowsInserted = 1, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                )
            ),
            totalRowsInserted = 1, totalRowsUpdated = 0, totalRowsSkipped = 0,
            totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 1,
        )
    }

    val failingExecutor: ImportExecutor = ImportExecutor { _, _, _, _ ->
        throw RuntimeException("simulated mid-import failure")
    }

    fun newRunner(stderr: (String) -> Unit, executor: ImportExecutor, store: FileCheckpointStore): DataImportRunner =
        DataImportRunner(
            targetResolver = { t, _ -> t ?: error("no target") },
            urlParser = {
                ConnectionConfig(DatabaseDialect.SQLITE, null, null, "/tmp/x.db", null, null)
            },
            poolFactory = { FakeConnectionPool() },
            writerLookup = { FakeDataWriter() },
            importExecutor = executor,
            stderr = stderr,
            checkpointStoreFactory = { store },
            inputResolutionHook = ParquetImportInputResolutionHook(),
        )

    fun request(source: String, resume: String?, checkpointDir: Path) = DataImportRequest(
        target = "sqlite:///tmp/x.db",
        source = source,
        format = "parquet",
        schema = null,
        table = null,
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
        resume = resume,
        checkpointDir = checkpointDir,
    )

    fun writeSingleFile(path: Path, rowValue: Long) {
        val schema = ChunkSchema(
            table = "public.users",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val provider = ParquetSingleFileManifestWriter(
            producerVersion = "0.9.8",
            clock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC),
        ).provider
        Files.newOutputStream(path).use { out ->
            ParquetChunkWriter(out, extraMetaDataProvider = provider).use { w ->
                w.begin("public.users", schema)
                w.write(DataChunk(table = "public.users", columns = emptyList(), rows = listOf(arrayOf<Any?>(rowValue)), chunkIndex = 0L))
                w.end()
            }
        }
    }

    fun persistedOpId(storeDir: Path): String =
        Files.list(storeDir).use { stream ->
            stream.map { it.fileName.toString() }
                .filter { it.endsWith(".checkpoint.yaml") }
                .findFirst()
                .orElseThrow { AssertionError("no persisted checkpoint in $storeDir") }
        }.removeSuffix(".checkpoint.yaml")

    test("Single-File-Resume Happy-Path: Fresh mit Checkpoint scheitert → Resume gleiche Datei → Exit 0") {
        val storeDir = Files.createTempDirectory("s9b3-happy-store-")
        val file = Files.createTempFile("s9b3-happy-", ".parquet")
        try {
            writeSingleFile(file, rowValue = 1L)
            val store = FileCheckpointStore(storeDir)

            // Phase 1: frischer Lauf MIT --checkpoint-dir → Hash wird berechnet
            // + persistiert (S9b-Fix); Executor scheitert → Exit 5, Manifest bleibt.
            val p1 = mutableListOf<String>()
            newRunner(p1::add, failingExecutor, store)
                .execute(request(file.toString(), resume = null, checkpointDir = storeDir)) shouldBe 5
            val opId = persistedOpId(storeDir)

            // Phase 2: Resume mit unveraenderter Datei → validateSingleFileResume
            // (Hash-Match) → Exit 0.
            val p2 = mutableListOf<String>()
            newRunner(p2::add, successExecutor, store)
                .execute(request(file.toString(), resume = opId, checkpointDir = storeDir)) shouldBe 0
        } finally {
            storeDir.toFile().deleteRecursively()
            Files.deleteIfExists(file)
        }
    }

    test("Single-File-Resume mit geänderter Datei → Exit 3 (PARQUET_SINGLE_FILE_CONTENT_CHANGED)") {
        val storeDir = Files.createTempDirectory("s9b3-changed-store-")
        val file = Files.createTempFile("s9b3-changed-", ".parquet")
        try {
            writeSingleFile(file, rowValue = 1L)
            val store = FileCheckpointStore(storeDir)

            val p1 = mutableListOf<String>()
            newRunner(p1::add, failingExecutor, store)
                .execute(request(file.toString(), resume = null, checkpointDir = storeDir)) shouldBe 5
            val opId = persistedOpId(storeDir)

            // Datei mit anderem Inhalt neu schreiben (gleiche Tabelle/Pfad →
            // optionsFingerprint unveraendert, aber contentSha256 differiert).
            writeSingleFile(file, rowValue = 99L)

            val p2 = mutableListOf<String>()
            val code = newRunner(p2::add, successExecutor, store)
                .execute(request(file.toString(), resume = opId, checkpointDir = storeDir))
            code shouldBe 3
            p2.joinToString("\n") shouldContain "content sha256 mismatch"
        } finally {
            storeDir.toFile().deleteRecursively()
            Files.deleteIfExists(file)
        }
    }
})
