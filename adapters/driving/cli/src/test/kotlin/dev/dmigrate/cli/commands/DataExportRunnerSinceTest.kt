package dev.dmigrate.cli.commands

import io.kotest.assertions.throwables.shouldThrow
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
import dev.dmigrate.format.data.ValueSerializer
import dev.dmigrate.streaming.ExportResult
import dev.dmigrate.streaming.TableExportSummary
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Unit-Tests für [DataExportRunner] mit Fakes für alle externen
 * Collaborators (sourceResolver, URL-Parser, Pool-Factory,
 * DataReader/TableLister-Lookups, WriterFactory, ExportExecutor).
 *
 * Damit wird **jeder Exit-Code-Pfad** aus Plan §6.10 (2/4/5/7/0) direkt
 * unit-testbar, ohne HikariCP, ohne echte Datenbank und ohne Clikt-Kontext.
 * Die E2E-Tests in `CliDataExportTest` bleiben als Integrations-Sicherheitsnetz,
 * decken aber nicht mehr jeden Fehlerpfad ab — das macht jetzt dieser Test.
 */
class DataExportRunnerSinceTest : FunSpec({

    // ─── Fakes ────────────────────────────────────────────────────

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

    /**
     * ExportExecutor-Fake der standardmäßig ein synthetisches erfolgreiches
     * Ergebnis liefert. Tests können den Builder überschreiben, um Fehler
     * zu werfen oder ein Result mit `error != null` zu liefern.
     */
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

    // ─── Helpers ──────────────────────────────────────────────────

    /** Baut einen [DataExportRequest] mit harmlosen Happy-Path-Defaults. */
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

    /**
     * Ein isolierter `sourceResolver`, der URLs mit "://" unverändert
     * durchreicht und benannte Quellen über einen neutralisierten
     * [NamedConnectionResolver] auflöst, damit Tests nicht vom
     * Host-Environment abhängen.
     */
    fun isolatedSourceResolver(source: String, configPath: Path?): String {
        val resolver = NamedConnectionResolver(
            configPathFromCli = configPath,
            envLookup = { null },
            defaultConfigPath = Path.of("/tmp/d-migrate-nonexistent-default-config.yaml"),
        )
        return resolver.resolve(source)
    }

    /** Capture-Helper, der stderr-Zeilen in eine Liste puffert. */
    class StderrCapture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined(): String = lines.joinToString("\n")
    }

    /**
     * Baut einen [DataExportRunner] mit Fake-Collaborators. Alle Parameter
     * sind optional; der Default ist ein voll funktionsfähiger Happy-Path-
     * Runner, der ohne echte DB, ohne echte Files, ohne Clikt läuft.
     */
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

    // ─── Happy path (Exit 0) ──────────────────────────────────────


    context("C.2 Fall 1 — ohne --since-column bleibt alles C.1-Verhalten") {
        test("no since-column + no manifest marker → silent C.1-fallback, no ResumeMarker passed") {
            val capturedMarkers = mutableListOf<Map<String, dev.dmigrate.driver.data.ResumeMarker>>()
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                capturedMarkers += resume.resumeMarkers
                val summaries = opts.tables.map {
                    TableExportSummary(it, rows = 1, chunks = 1, bytes = 10, durationMs = 1)
                }
                ExportResult(summaries, 1L * opts.tables.size, opts.tables.size.toLong(), 10L * opts.tables.size, 1)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                // PK present — but without since-column, the runner still
                // should not build markers.
                primaryKeyLookup = { _, _, _ -> listOf("id") },
            )
            runner.execute(request()) shouldBe 0
            capturedMarkers.single().size shouldBe 0
        }
    }

    context("C.2 Fall 2 — --since-column ohne PK → stderr-Hinweis + C.1-Fallback") {
        test("since-column set but no PK: stderr warning, no ResumeMarker for that table") {
            val capturedMarkers = mutableListOf<Map<String, dev.dmigrate.driver.data.ResumeMarker>>()
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                capturedMarkers += resume.resumeMarkers
                val summaries = opts.tables.map {
                    TableExportSummary(it, rows = 1, chunks = 1, bytes = 10, durationMs = 1)
                }
                ExportResult(summaries, 1L * opts.tables.size, opts.tables.size.toLong(), 10L * opts.tables.size, 1)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                primaryKeyLookup = { _, _, _ -> emptyList() }, // no PK
            )
            val exit = runner.execute(
                request(sinceColumn = "updated_at", since = "2026-01-01")
            )
            exit shouldBe 0
            capturedMarkers.single().size shouldBe 0
            stderr.joined() shouldContain "mid-table resume disabled for table 'users'"
            stderr.joined() shouldContain "no primary key"
        }
    }

    context("C.2 Fresh-Track — --since-column + PK → ResumeMarker ohne Position") {
        test("fresh run with since-column + PK gets ResumeMarker with position=null") {
            val capturedMarkers = mutableListOf<Map<String, dev.dmigrate.driver.data.ResumeMarker>>()
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                capturedMarkers += resume.resumeMarkers
                val summaries = opts.tables.map {
                    TableExportSummary(it, rows = 1, chunks = 1, bytes = 10, durationMs = 1)
                }
                ExportResult(summaries, 1L * opts.tables.size, opts.tables.size.toLong(), 10L * opts.tables.size, 1)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                primaryKeyLookup = { _, _, _ -> listOf("id") },
            )
            runner.execute(
                request(sinceColumn = "updated_at", since = "2026-01-01")
            ) shouldBe 0
            val markers = capturedMarkers.single()
            markers.size shouldBe 1
            val marker = markers.getValue("users")
            marker.markerColumn shouldBe "updated_at"
            marker.tieBreakerColumns shouldContainExactly listOf("id")
            marker.position shouldBe null
        }
    }

    context("C.2 Fall 3 — Manifest lastMarker without --since-column → Exit 3") {
        test("manifest has resumePosition but current request has no --since-column → Exit 3") {
            val storeDir = Files.createTempDirectory("d-migrate-c2-f3-")
            val opId = "c2-fall3-op"
            val manifestPath = storeDir.resolve("$opId.checkpoint.yaml")
            val warmed = dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                operationId = opId,
                operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.EXPORT,
                createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                format = "json",
                chunkSize = 10_000,
                tableSlices = listOf(
                    dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                        table = "users",
                        status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.IN_PROGRESS,
                        rowsProcessed = 5,
                        chunksProcessed = 1,
                        resumePosition = dev.dmigrate.streaming.checkpoint.CheckpointResumePosition(
                            markerColumn = "updated_at",
                            markerValue = "2026-04-10",
                            tieBreakerColumns = listOf("id"),
                            tieBreakerValues = listOf("42"),
                        ),
                    ),
                ),
                optionsFingerprint = ExportOptionsFingerprint.compute(
                    ExportOptionsFingerprint.Input(
                        format = "json",
                        encoding = "utf-8",
                        csvDelimiter = ",",
                        csvBom = false,
                        csvNoHeader = false,
                        csvNullString = "",
                        filter = null,
                        sinceColumn = null,
                        since = null,
                        tables = listOf("users"),
                        outputMode = "single-file",
                        outputPath = storeDir.resolve("out.json").toAbsolutePath()
                            .normalize().toString(),
                        primaryKeysByTable = emptyMap(),
                    )
                ),
            )
            dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir).save(warmed)
            manifestPath.toFile().exists() shouldBe true

            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { dir ->
                    dev.dmigrate.streaming.checkpoint.FileCheckpointStore(dir)
                },
            )
            val exit = runner.execute(
                request(
                    output = storeDir.resolve("out.json"),
                    tables = listOf("users"),
                    resume = opId,
                    checkpointDir = storeDir,
                    // KEIN since-column — Fall 3
                ),
            )
            exit shouldBe 3
            stderr.joined() shouldContain "mid-table marker on column 'updated_at'"
        }
    }

})
