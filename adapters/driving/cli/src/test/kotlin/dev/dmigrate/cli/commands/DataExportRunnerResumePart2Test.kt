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
class DataExportRunnerResumePart2Test : FunSpec({

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

    class InMemoryCheckpointStore : dev.dmigrate.streaming.checkpoint.CheckpointStore {
        val state = mutableMapOf<String, dev.dmigrate.streaming.checkpoint.CheckpointManifest>()
        val saveCount = mutableMapOf<String, Int>()
        var completeCount: Int = 0
        override fun load(operationId: String) = state[operationId]
        override fun save(manifest: dev.dmigrate.streaming.checkpoint.CheckpointManifest) {
            state[manifest.operationId] = manifest
            saveCount[manifest.operationId] =
                (saveCount[manifest.operationId] ?: 0) + 1
        }
        override fun list() = state.values.map {
            dev.dmigrate.streaming.checkpoint.CheckpointReference(
                operationId = it.operationId,
                operationType = it.operationType,
                schemaVersion = it.schemaVersion,
            )
        }
        override fun complete(operationId: String) {
            state.remove(operationId)
            completeCount++
        }
    }

    context("Phase C.1 — Resume-Preflight (continued)") {

        test("checkpointConfigResolver-Directory gewinnt, wenn kein CLI-Override gesetzt ist") {
            val store = InMemoryCheckpointStore()
            var storeDir: Path? = null
            val stderr = StderrCapture()
            val configDir = Path.of("/tmp/d-migrate-c1-from-cfg")
            val executor = ExportExecutor { ctx, opts, resume, callbacks ->
                val summaries = opts.tables.map {
                    dev.dmigrate.streaming.TableExportSummary(
                        table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                    )
                }
                summaries.forEach(callbacks.onTableCompleted)
                dev.dmigrate.streaming.ExportResult(
                    tables = summaries, totalRows = 1L, totalChunks = 1L,
                    totalBytes = 1L, durationMs = 1L,
                )
            }
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                checkpointStoreFactory = { dir ->
                    storeDir = dir
                    store
                },
                checkpointConfigResolver = { _ ->
                    dev.dmigrate.streaming.CheckpointConfig(
                        enabled = true,
                        directory = configDir,
                    )
                },
            )
            // Wichtig: request.checkpointDir bleibt null → Config muss
            // greifen.
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-out"),
                    splitFiles = true,
                    checkpointDir = null,
                ),
            )
            exit shouldBe 0
            storeDir shouldBe configDir
        }

        test("CLI-Override --checkpoint-dir sticht die Config") {
            val store = InMemoryCheckpointStore()
            var storeDir: Path? = null
            val stderr = StderrCapture()
            val configDir = Path.of("/tmp/d-migrate-c1-cfg")
            val cliDir = Path.of("/tmp/d-migrate-c1-cli")
            val executor = ExportExecutor { ctx, opts, resume, callbacks ->
                opts.tables.forEach {
                    callbacks.onTableCompleted(
                        dev.dmigrate.streaming.TableExportSummary(
                            table = it, rows = 0, chunks = 1, bytes = 0, durationMs = 1,
                        ),
                    )
                }
                dev.dmigrate.streaming.ExportResult(
                    tables = emptyList(), totalRows = 0, totalChunks = 0,
                    totalBytes = 0, durationMs = 1,
                )
            }
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                checkpointStoreFactory = { dir ->
                    storeDir = dir
                    store
                },
                checkpointConfigResolver = { _ ->
                    dev.dmigrate.streaming.CheckpointConfig(directory = configDir)
                },
            )
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-override-out"),
                    splitFiles = true,
                    checkpointDir = cliDir,
                ),
            )
            exit shouldBe 0
            storeDir shouldBe cliDir
        }

        // 0.9.0 Phase C.1 Review-Fix (§5.5): Single-File-Resume mit der
        // einzigen Tabelle bereits COMPLETED → Exit 3 statt silent success.

        test("--resume bei single-file + einziger Tabelle COMPLETED endet mit Exit 3") {
            val store = InMemoryCheckpointStore()

            // Vorwärmen: denselben Lauf einmal "abschliessen" lassen,
            // damit wir das gleiche Fingerprint haben wie beim Resume.
            var firstOpId: String? = null
            val captureStore = InMemoryCheckpointStore()
            val warmRunner = newRunner(
                StderrCapture(),
                exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                    // Phase C.2 §5.4: der Runner leitet Single-File-Laeufe
                    // in eine Staging-Datei im Checkpoint-Verzeichnis um.
                    // Der Fake simuliert den echten Writer, indem er die
                    // uebergebene Output-Datei anlegt, damit die
                    // spaetere atomic-rename-Operation einen Gegenstand
                    // hat.
                    val out = opts.output
                    if (out is dev.dmigrate.streaming.ExportOutput.SingleFile) {
                        Files.createDirectories(out.path.parent)
                        Files.writeString(out.path, "")
                    }
                    opts.tables.forEach {
                        callbacks.onTableCompleted(
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                            ),
                        )
                    }
                    dev.dmigrate.streaming.ExportResult(
                        tables = opts.tables.map {
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                            )
                        },
                        totalRows = 1, totalChunks = 1, totalBytes = 1, durationMs = 1,
                    )
                },
                checkpointStoreFactory = { _ ->
                    object : dev.dmigrate.streaming.checkpoint.CheckpointStore by captureStore {
                        override fun complete(operationId: String) {
                            // Behalten, damit wir es beim Resume lesen koennen.
                            firstOpId = operationId
                        }
                    }
                },
            )
            warmRunner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-sf-out.json"),
                    splitFiles = false,
                    tables = listOf("users"),
                    checkpointDir = Path.of("/tmp/d-migrate-c1-sf-ckpt"),
                ),
            )
            val warmedOpId = firstOpId!!
            val warmedManifest = captureStore.state.getValue(warmedOpId)

            // Resume-Lauf startet mit genau dem COMPLETED-Manifest.
            store.save(warmedManifest)
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { store },
            )
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-sf-out.json"),
                    splitFiles = false,
                    tables = listOf("users"),
                    resume = warmedOpId,
                    checkpointDir = Path.of("/tmp/d-migrate-c1-sf-ckpt"),
                ),
            )
            exit shouldBe 3
            stderr.joined() shouldContain "single-file resume"
            stderr.joined() shouldContain "already completed"
        }

        test("E2: simulated export abort — resume skips completed table and finishes") {
            // Scenario: two-table export where table 1 completes but
            // table 2 triggers an executor failure (simulated abort).
            // A second run with --resume must skip the already-completed
            // table and only process the remaining one.
            val store = InMemoryCheckpointStore()
            val checkpointDir = Path.of("/tmp/d-migrate-e2-export-ckpt")
            val outputDir = Path.of("/tmp/d-migrate-e2-export-out")

            // Run 1: "users" completes via onTableCompleted, executor
            // throws when processing "orders" → Exit 5.
            val run1Executor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                for (t in opts.tables) {
                    if (t in resume.skippedTables) continue
                    if (t == "orders") throw RuntimeException("simulated I/O failure on orders")
                    callbacks.onTableCompleted(TableExportSummary(table = t, rows = 10, chunks = 1, bytes = 256, durationMs = 1))
                }
                error("unreachable — executor throws above")
            }
            val stderr1 = StderrCapture()
            val runner1 = newRunner(
                stderr1,
                exportExecutor = run1Executor,
                checkpointStoreFactory = { store },
            )
            val exit1 = runner1.execute(
                request(
                    output = outputDir,
                    tables = listOf("users", "orders"),
                    splitFiles = true,
                    checkpointDir = checkpointDir,
                ),
            )
            exit1 shouldBe 5

            // Verify manifest state: "users" COMPLETED, "orders" still PENDING.
            store.state.size shouldBe 1
            val manifest = store.state.values.single()
            manifest.tableSlices.first { it.table == "users" }.status shouldBe
                dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.COMPLETED
            manifest.tableSlices.first { it.table == "orders" }.status shouldBe
                dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.PENDING

            // Run 2: resume from the stored manifest. Executor should
            // receive skippedTables = {"users"} and only process "orders".
            val capturedSkipped = mutableSetOf<String>()
            val run2Executor = ExportExecutor {
                ctx, opts, resume, callbacks,
                ->
                capturedSkipped += resume.skippedTables
                val processed = opts.tables.filter { it !in resume.skippedTables }
                val summaries = processed.map {
                    TableExportSummary(table = it, rows = 5, chunks = 1, bytes = 128, durationMs = 1)
                        .also(callbacks.onTableCompleted)
                }
                ExportResult(
                    tables = summaries,
                    totalRows = summaries.sumOf { it.rows },
                    totalChunks = summaries.sumOf { it.chunks },
                    totalBytes = summaries.sumOf { it.bytes },
                    durationMs = 1,
                )
            }
            val stderr2 = StderrCapture()
            val runner2 = newRunner(
                stderr2,
                exportExecutor = run2Executor,
                checkpointStoreFactory = { store },
            )
            val exit2 = runner2.execute(
                request(
                    output = outputDir,
                    tables = listOf("users", "orders"),
                    splitFiles = true,
                    resume = manifest.operationId,
                    checkpointDir = checkpointDir,
                ),
            )
            exit2 shouldBe 0
            capturedSkipped shouldBe setOf("users")
            store.completeCount shouldBe 1
            store.state shouldBe emptyMap() // complete() removed manifest
        }
    }

    // ─── 0.9.0 Phase C.2: 3 Fallunterscheidungen + fingerprint ────
    // (`docs/ImpPlan-0.9.0-C2.md` §4.1 / §5.3)
    // ──────────────────────────────────────────────────────────────

})
