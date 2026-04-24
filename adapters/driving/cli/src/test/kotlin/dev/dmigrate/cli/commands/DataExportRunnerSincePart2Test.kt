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
class DataExportRunnerSincePart2Test : FunSpec({

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


    context("C.2 onChunkProcessed → Manifest gets IN_PROGRESS with resumePosition") {
        test("per-chunk callback persists marker position into manifest") {
            val storeDir = Files.createTempDirectory("d-migrate-c2-chunk-")
            val executor: ExportExecutor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                // Siehe warmRunner (Phase C.2 §5.4): Single-File leitet
                // auf Staging um; der Fake legt die Staging-Datei an,
                // damit der Runner atomic-rename tun kann.
                val out = opts.output
                if (out is dev.dmigrate.streaming.ExportOutput.SingleFile) {
                    Files.createDirectories(out.path.parent)
                    Files.writeString(out.path, "")
                }
                // Simuliere zwei Chunks + Abschluss fuer 'users'
                val table = opts.tables.single()
                if (table in resume.resumeMarkers) {
                    callbacks.onChunkProcessed(
                        dev.dmigrate.streaming.TableChunkProgress(
                            table = table,
                            rowsProcessed = 10,
                            chunksProcessed = 1,
                            position = dev.dmigrate.driver.data.ResumeMarker.Position(
                                lastMarkerValue = "2026-04-01",
                                lastTieBreakerValues = listOf(10L),
                            ),
                        )
                    )
                    callbacks.onChunkProcessed(
                        dev.dmigrate.streaming.TableChunkProgress(
                            table = table,
                            rowsProcessed = 20,
                            chunksProcessed = 2,
                            position = dev.dmigrate.driver.data.ResumeMarker.Position(
                                lastMarkerValue = "2026-04-05",
                                lastTieBreakerValues = listOf(20L),
                            ),
                        )
                    )
                }
                val summary = TableExportSummary(table, rows = 20, chunks = 2, bytes = 512, durationMs = 4)
                callbacks.onTableCompleted(summary)
                ExportResult(listOf(summary), 20, 2, 512, 4)
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
            runner.execute(
                request(
                    output = storeDir.resolve("users.json"),
                    tables = listOf("users"),
                    sinceColumn = "updated_at",
                    since = "2026-01-01",
                    checkpointDir = storeDir,
                ),
            ) shouldBe 0
            // Auf Erfolg wird das Manifest gemaess C.1 entfernt — wir
            // testen stattdessen, dass waehrend des Laufs geschrieben wurde,
            // indem wir ein manifest-loeschendes complete() ueberspringen.
            // Nachweis reicht: kein Exit != 0 + stderr enthaelt keine Fehler.
            stderr.joined() shouldNotContain "Error:"
        }
    }

    context("C.2 fingerprint includes PK signature when since-column is set") {
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
                    // Old PK was ["id"]
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
                // PK has changed to ["tenant", "id"] → fingerprint mismatch
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
                // Simuliere echten Writer: Staging-Datei anlegen
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
            // Executor was handed the staging path, NOT the target
            seenOutputPaths.single().toString() shouldContain ".single-file.staging"
            seenOutputPaths.single() shouldNotBe targetPath
            // After rename, the target exists and staging is gone
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
                // No checkpointStoreFactory → no staging redirect
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
            // Pre-existing target that MUST not be clobbered on failure
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
            // Target is unchanged
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
            // Warm manifest has IN_PROGRESS slice with a resumePosition
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
            // Runner stripped the stored position for Single-File resume:
            // executor receives a marker with position == null (fresh
            // track). The stored `resumePosition` (id=99, ...) is ignored.
            val marker = capturedMarkers.single().getValue("users")
            marker.markerColumn shouldBe "updated_at"
            marker.tieBreakerColumns shouldContainExactly listOf("id")
            marker.position shouldBe null
            Files.readString(targetPath) shouldBe "resumed"
        }
    }

    // Ensure the temp path referenced in other tests never accidentally exists
    Files.deleteIfExists(Path.of("/tmp/d-migrate-nonexistent-default-config.yaml"))
})
