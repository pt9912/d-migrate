package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.TableExportSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

class DataExportRunnerMarkerTestPart2 : FunSpec({

    class FakeConnectionPool(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : ConnectionPool {
        var closeCount: Int = 0
        override fun borrow(): Connection =
            error("FakeConnectionPool.borrow() must not be called in runner unit tests")
        override fun activeConnections(): Int = 0
        override fun close() { closeCount++ }
    }

    class FakeDataReader(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : DataReader {
        override fun streamTable(
            pool: ConnectionPool,
            table: String,
            filter: DataFilter?,
            chunkSize: Int,
        ): ChunkSequence =
            error("FakeDataReader.streamTable() must not be called — runner delegates to ExportExecutor")
    }

    class FakeTableLister(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
        val provider: () -> List<String> = { listOf("users") },
    ) : TableLister {
        override fun listTables(pool: ConnectionPool): List<String> = provider()
    }

    class FakeWriterFactory : DataChunkWriterFactory {
        override fun create(
            format: DataExportFormat,
            output: OutputStream,
            options: ExportOptions,
        ): DataChunkWriter =
            error("FakeWriterFactory.create() must not be called — runner delegates to ExportExecutor")
    }

    val successExecutor: ExportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
        val summaries = opts.tables.map { TableExportSummary(it, rows = 10, chunks = 1, bytes = 256, durationMs = 3) }
        ExportResult(
            tables = summaries,
            totalRows = 10L * opts.tables.size,
            totalChunks = opts.tables.size.toLong(),
            totalBytes = 256L * opts.tables.size,
            durationMs = 3,
        )
    }

    fun request(
        source: String = "sqlite:///tmp/d-migrate-runner-fake.db",
        format: String = "json",
        output: Path? = null,
        tables: List<String>? = listOf("users"),
        filter: String? = null,
        sinceColumn: String? = null,
        since: String? = null,
        encoding: String = "utf-8",
        chunkSize: Int = 10_000,
        splitFiles: Boolean = false,
        csvDelimiter: String = ",",
        csvBom: Boolean = false,
        csvNoHeader: Boolean = false,
        nullString: String = "",
        cliConfigPath: Path? = null,
        quiet: Boolean = false,
        noProgress: Boolean = false,
        resume: String? = null,
        checkpointDir: Path? = null,
    ) = DataExportRequest(
        source = source,
        format = format,
        output = output,
        tables = tables,
        filter = parseFilter(filter),
        sinceColumn = sinceColumn,
        since = since,
        encoding = encoding,
        chunkSize = chunkSize,
        splitFiles = splitFiles,
        csvDelimiter = csvDelimiter,
        csvBom = csvBom,
        csvNoHeader = csvNoHeader,
        nullString = nullString,
        cliConfigPath = cliConfigPath,
        quiet = quiet,
        noProgress = noProgress,
        resume = resume,
        checkpointDir = checkpointDir,
    )

    fun isolatedSourceResolver(source: String, configPath: Path?): String {
        val resolver = NamedConnectionResolver(
            configPathFromCli = configPath,
            envLookup = { null },
            defaultConfigPath = Path.of("/tmp/d-migrate-nonexistent-default-config.yaml"),
        )
        return resolver.resolve(source)
    }

    class StderrCapture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined(): String = lines.joinToString("\n")
    }

    fun newRunner(
        stderr: StderrCapture,
        sourceResolver: (String, Path?) -> String = ::isolatedSourceResolver,
        urlParser: (String) -> ConnectionConfig = ConnectionUrlParser::parse,
        poolFactory: (ConnectionConfig) -> ConnectionPool = { FakeConnectionPool() },
        readerLookup: (DatabaseDialect) -> DataReader = { FakeDataReader() },
        listerLookup: (DatabaseDialect) -> TableLister = { FakeTableLister() },
        writerFactoryBuilder: () -> DataChunkWriterFactory = { FakeWriterFactory() },
        collectWarnings: () -> List<String> = { emptyList() },
        exportExecutor: ExportExecutor = successExecutor,
        progressReporter: dev.dmigrate.streaming.ProgressReporter = dev.dmigrate.streaming.NoOpProgressReporter,
        checkpointStoreFactory: ((Path) -> dev.dmigrate.streaming.checkpoint.CheckpointStore)? = null,
        checkpointConfigResolver: (Path?) -> dev.dmigrate.streaming.CheckpointConfig? = { null },
        clock: () -> java.time.Instant = java.time.Instant::now,
        primaryKeyLookup: (ConnectionPool, DatabaseDialect, String) -> List<String> =
            { _, _, _ -> emptyList() },
    ): DataExportRunner = DataExportRunner(
        sourceResolver = sourceResolver,
        urlParser = urlParser,
        poolFactory = poolFactory,
        readerLookup = readerLookup,
        listerLookup = listerLookup,
        writerFactoryBuilder = writerFactoryBuilder,
        collectWarnings = collectWarnings,
        exportExecutor = exportExecutor,
        progressReporter = progressReporter,
        stderr = stderr.sink,
        checkpointStoreFactory = checkpointStoreFactory,
        checkpointConfigResolver = checkpointConfigResolver,
        clock = clock,
        primaryKeyLookup = primaryKeyLookup,
    )

    // ─── C.2 Fall 1 — ohne --since-column bleibt alles C.1-Verhalten ─

    context("C.2 Fall 1 — ohne --since-column bleibt alles C.1-Verhalten") {

        test("PK change invalidates resume (fingerprint mismatch → Exit 3)") {
            val storeDir = Files.createTempDirectory("d-migrate-c2-fp-")
            val opId = "c2-fp-op"
            val outputPath = storeDir.resolve("users.json")
            val fpOld = ExportOptionsFingerprint.compute(
                ExportOptionsFingerprint.Input(
                    format = "json", encoding = "utf-8", csvDelimiter = ",", csvBom = false,
                    csvNoHeader = false, csvNullString = "",
                    filter = null, sinceColumn = "updated_at", since = "2026-01-01",
                    tables = listOf("users"),
                    outputMode = "single-file",
                    outputPath = outputPath.toAbsolutePath().normalize().toString(),
                    primaryKeysByTable = mapOf("users" to listOf("id")),
                )
            )
            val warmed = dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                operationId = opId,
                operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.EXPORT,
                createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                format = "json", chunkSize = 10_000,
                tableSlices = listOf(
                    dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                        table = "users",
                        status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.IN_PROGRESS,
                    ),
                ),
                optionsFingerprint = fpOld,
            )
            dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir).save(warmed)

            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { dir ->
                    dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
                },
                primaryKeyLookup = { _, _, _ -> listOf("tenant", "id") },
            )
            val exit = runner.execute(
                request(
                    output = outputPath,
                    tables = listOf("users"),
                    sinceColumn = "updated_at",
                    since = "2026-01-01",
                    resume = opId,
                    checkpointDir = storeDir,
                ),
            )
            exit shouldBe 3
            stderr.joined() shouldContain "fingerprint mismatch"
        }
    }

    context("C.2.6 Single-File staging → atomic rename to target") {
        test("fresh single-file run goes through staging, target only appears on success") {
            val storeDir = Files.createTempDirectory("d-migrate-c26-fresh-")
            val targetPath = storeDir.resolve("users.json")
            val seenOutputPaths = mutableListOf<Path>()
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                val out = opts.output
                require(out is dev.dmigrate.streaming.ExportOutput.SingleFile)
                seenOutputPaths.add(out.path)
                Files.writeString(out.path, """[{"id":1}]""")
                val summary = TableExportSummary(
                    opts.tables.single(), rows = 1, chunks = 1, bytes = 10, durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ExportResult(listOf(summary), 1, 1, 10, 1)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                checkpointStoreFactory = { dir ->
                    dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
                },
            )
            val exit = runner.execute(
                request(
                    output = targetPath,
                    tables = listOf("users"),
                    checkpointDir = storeDir,
                ),
            )
            exit shouldBe 0
            seenOutputPaths.single().toString() shouldContain ".single-file.staging"
            seenOutputPaths.single() shouldNotBe targetPath
            Files.exists(targetPath) shouldBe true
            Files.exists(seenOutputPaths.single()) shouldBe false
            Files.readString(targetPath) shouldBe """[{"id":1}]"""
        }

        test("without checkpoint-dir configured, single-file writes directly to target (no staging)") {
            val tmpDir = Files.createTempDirectory("d-migrate-c26-nocp-")
            val targetPath = tmpDir.resolve("users.json")
            val seenOutputPaths = mutableListOf<Path>()
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                val out = opts.output
                require(out is dev.dmigrate.streaming.ExportOutput.SingleFile)
                seenOutputPaths.add(out.path)
                Files.writeString(out.path, """[{"id":1}]""")
                val summary = TableExportSummary(
                    opts.tables.single(), rows = 1, chunks = 1, bytes = 10, durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ExportResult(listOf(summary), 1, 1, 10, 1)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
            )
            runner.execute(
                request(
                    output = targetPath,
                    tables = listOf("users"),
                ),
            ) shouldBe 0
            seenOutputPaths.single() shouldBe targetPath
            Files.exists(targetPath) shouldBe true
        }

        test("failed executor leaves staging + keeps target untouched") {
            val storeDir = Files.createTempDirectory("d-migrate-c26-fail-")
            val targetPath = storeDir.resolve("users.json")
            Files.writeString(targetPath, "PRE_EXISTING")
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                throw RuntimeException("simulated stream failure")
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                checkpointStoreFactory = { dir ->
                    dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
                },
            )
            val exit = runner.execute(
                request(
                    output = targetPath,
                    tables = listOf("users"),
                    checkpointDir = storeDir,
                ),
            )
            exit shouldBe 5
            Files.readString(targetPath) shouldBe "PRE_EXISTING"
        }

        test("single-file resume discards stored position; executor receives marker with position=null") {
            val storeDir = Files.createTempDirectory("d-migrate-c26-resume-")
            val targetPath = storeDir.resolve("users.json")
            val opId = "c26-resume-op"
            val outputPath = targetPath
            val fingerprint = ExportOptionsFingerprint.compute(
                ExportOptionsFingerprint.Input(
                    format = "json", encoding = "utf-8", csvDelimiter = ",",
                    csvBom = false, csvNoHeader = false, csvNullString = "",
                    filter = null, sinceColumn = "updated_at", since = "2026-01-01",
                    tables = listOf("users"),
                    outputMode = "single-file",
                    outputPath = outputPath.toAbsolutePath().normalize().toString(),
                    primaryKeysByTable = mapOf("users" to listOf("id")),
                )
            )
            val warmed = dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                operationId = opId,
                operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.EXPORT,
                createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                format = "json", chunkSize = 10_000,
                tableSlices = listOf(
                    dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                        table = "users",
                        status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.IN_PROGRESS,
                        rowsProcessed = 50, chunksProcessed = 5,
                        resumePosition = dev.dmigrate.streaming.checkpoint.CheckpointResumePosition(
                            markerColumn = "updated_at",
                            markerValue = "2026-03-01",
                            tieBreakerColumns = listOf("id"),
                            tieBreakerValues = listOf("99"),
                        ),
                    ),
                ),
                optionsFingerprint = fingerprint,
            )
            dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir).save(warmed)

            val capturedMarkers = mutableListOf<Map<String, dev.dmigrate.driver.data.ResumeMarker>>()
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                val out = opts.output
                require(out is dev.dmigrate.streaming.ExportOutput.SingleFile)
                capturedMarkers += resume.resumeMarkers
                Files.writeString(out.path, "resumed")
                val summary = TableExportSummary(opts.tables.single(), 1, 1, 10, 1)
                callbacks.onTableCompleted(summary)
                ExportResult(listOf(summary), 1, 1, 10, 1)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                primaryKeyLookup = { _, _, _ -> listOf("id") },
                checkpointStoreFactory = { dir ->
                    dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
                },
            )
            val exit = runner.execute(
                request(
                    output = targetPath,
                    tables = listOf("users"),
                    sinceColumn = "updated_at",
                    since = "2026-01-01",
                    resume = opId,
                    checkpointDir = storeDir,
                ),
            )
            exit shouldBe 0
            val marker = capturedMarkers.single().getValue("users")
            marker.markerColumn shouldBe "updated_at"
            marker.tieBreakerColumns shouldContainExactly listOf("id")
            marker.position shouldBe null
            Files.readString(targetPath) shouldBe "resumed"
        }
    }

    Files.deleteIfExists(Path.of("/tmp/d-migrate-nonexistent-default-config.yaml"))
})
