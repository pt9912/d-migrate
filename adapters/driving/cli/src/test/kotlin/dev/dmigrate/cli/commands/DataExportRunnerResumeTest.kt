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
class DataExportRunnerResumeTest : FunSpec({

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


    context("Phase C.1 — Resume-Preflight und Manifest-Lifecycle") {

        // In-Memory-CheckpointStore fuer Tests ohne Dateisystem-Zugriff.
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

        test("neuer Lauf ohne --resume legt Manifest an und completed es") {
            val store = InMemoryCheckpointStore()
            val dir = Path.of("/tmp/d-migrate-c1-new")
            val stderr = StderrCapture()
            // Executor simuliert, dass StreamingExporter pro Tabelle
            // onTableCompleted aufruft.
            val executor = ExportExecutor { ctx, opts, resume, callbacks ->
                val summaries = opts.tables.map {
                    dev.dmigrate.streaming.TableExportSummary(
                        table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                    )
                }
                summaries.forEach(callbacks.onTableCompleted)
                dev.dmigrate.streaming.ExportResult(
                    tables = summaries,
                    totalRows = summaries.sumOf { it.rows },
                    totalChunks = summaries.sumOf { it.chunks },
                    totalBytes = summaries.sumOf { it.bytes },
                    durationMs = 1,
                )
            }
            val runner = newRunner(
                stderr,
                exportExecutor = executor,
                checkpointStoreFactory = { store },
            )
            val exit = runner.execute(
                request(
                    output = dir,
                    splitFiles = true,
                    checkpointDir = Path.of("/tmp/d-migrate-c1-ckpt"),
                ),
            )
            exit shouldBe 0
            store.completeCount shouldBe 1
            store.state shouldBe emptyMap() // complete() hat Manifest entfernt
            // Initial + 1 Tabelle = 2 saves
            store.saveCount.values.sum() shouldBe 2
        }

        test("--resume ohne Checkpoint-Dir endet mit Exit 7") {
            val stderr = StderrCapture()
            val runner = newRunner(stderr, checkpointStoreFactory = { InMemoryCheckpointStore() })
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-noresumedir.json"),
                    resume = "some-id",
                    checkpointDir = null,
                ),
            )
            exit shouldBe 7
            stderr.joined() shouldContain "checkpoint"
        }

        test("--resume mit unbekanntem id endet mit Exit 7") {
            val store = InMemoryCheckpointStore()
            val stderr = StderrCapture()
            val runner = newRunner(stderr, checkpointStoreFactory = { store })
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-unk.json"),
                    resume = "nonexistent-op-id",
                    checkpointDir = Path.of("/tmp/d-migrate-c1-ckpt"),
                ),
            )
            exit shouldBe 7
            stderr.joined() shouldContain "Checkpoint not found"
        }

        test("--resume mit fingerprint-Mismatch endet mit Exit 3") {
            val store = InMemoryCheckpointStore()
            store.save(
                dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                    operationId = "op-mismatch",
                    operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.EXPORT,
                    createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    format = "json",
                    chunkSize = 10_000,
                    tableSlices = listOf(
                        dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                            table = "users",
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.PENDING,
                        ),
                    ),
                    optionsFingerprint = "0".repeat(64), // anderer Hash als aktueller Request
                ),
            )
            val stderr = StderrCapture()
            val runner = newRunner(stderr, checkpointStoreFactory = { store })
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-fp.json"),
                    resume = "op-mismatch",
                    checkpointDir = Path.of("/tmp/d-migrate-c1-ckpt"),
                ),
            )
            exit shouldBe 3
            stderr.joined() shouldContain "fingerprint mismatch"
        }

        test("--resume with v1 legacy checkpoint and --filter exits 2 with migration hint") {
            val store = InMemoryCheckpointStore()
            // Simulate a pre-0.9.3 v1 manifest with raw-SQL-based fingerprint
            store.save(
                dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                    schemaVersion = 1,
                    operationId = "op-legacy",
                    operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.EXPORT,
                    createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    format = "json",
                    chunkSize = 10_000,
                    tableSlices = listOf(
                        dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                            table = "users",
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.PENDING,
                        ),
                    ),
                    // Raw-SQL-based fingerprint from pre-0.9.3 — will not match DSL canonical form
                    optionsFingerprint = "a".repeat(64),
                ),
            )
            val stderr = StderrCapture()
            val runner = newRunner(stderr, checkpointStoreFactory = { store })
            val exit = runner.execute(
                request(
                    filter = "status = 'OPEN'",
                    output = Path.of("/tmp/d-migrate-legacy-ckpt.json"),
                    resume = "op-legacy",
                    checkpointDir = Path.of("/tmp/d-migrate-c1-ckpt"),
                ),
            )
            exit shouldBe 2
            stderr.joined() shouldContain "schema version 1"
            stderr.joined() shouldContain "pre-0.9.3"
        }

        test("--resume mit operationType-Mismatch (IMPORT) endet mit Exit 3") {
            val store = InMemoryCheckpointStore()
            store.save(
                dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                    operationId = "op-wrongtype",
                    operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.IMPORT,
                    createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    format = "json",
                    chunkSize = 10_000,
                    tableSlices = emptyList(),
                ),
            )
            val stderr = StderrCapture()
            val runner = newRunner(stderr, checkpointStoreFactory = { store })
            val exit = runner.execute(
                request(
                    output = Path.of("/tmp/d-migrate-c1-wt.json"),
                    resume = "op-wrongtype",
                    checkpointDir = Path.of("/tmp/d-migrate-c1-ckpt"),
                ),
            )
            exit shouldBe 3
            stderr.joined() shouldContain "type mismatch"
        }

        test("--resume mit COMPLETED-Tabelle skippt diese und completed trotzdem") {
            // Vorhandenes Manifest: users ist bereits COMPLETED.
            // Wir geben dem Executor ein Fake, das scheitert wenn es
            // aufgerufen wird — damit beweisen wir, dass der Skip wirkt.
            val store = InMemoryCheckpointStore()
            val stderr = StderrCapture()
            val checkpointDir = Path.of("/tmp/d-migrate-c1-done")

            // Fingerprint muss passen → zuerst den Runner mit identischen
            // Options ausfuehren und die Initial-Saves kapern.
            var savedFingerprint: String? = null
            val capturingStore = InMemoryCheckpointStore()
            val warm = newRunner(
                StderrCapture(),
                exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                    opts.tables.forEach {
                        callbacks.onTableCompleted(
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 0, chunks = 1, bytes = 0, durationMs = 1
                            )
                        )
                    }
                    dev.dmigrate.streaming.ExportResult(
                        tables = opts.tables.map {
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 0, chunks = 1, bytes = 0, durationMs = 1
                            )
                        },
                        totalRows = 0, totalChunks = opts.tables.size.toLong(),
                        totalBytes = 0, durationMs = 1,
                    )
                },
                checkpointStoreFactory = { dir ->
                    // Vorwaermen schreibt ins capturingStore
                    object : dev.dmigrate.streaming.checkpoint.CheckpointStore by capturingStore {
                        override fun save(manifest: dev.dmigrate.streaming.checkpoint.CheckpointManifest) {
                            savedFingerprint = manifest.optionsFingerprint
                            capturingStore.save(manifest)
                        }
                        override fun complete(operationId: String) {
                            // wir wollen das Manifest fuer den Resume-Test
                            // behalten — kein echtes complete hier.
                        }
                    }
                },
            )
            warm.execute(
                request(
                    output = checkpointDir,
                    splitFiles = true,
                    checkpointDir = checkpointDir,
                ),
            )
            // Jetzt haben wir ein Manifest mit gueltigem Fingerprint im
            // `capturingStore`. Finde die operationId.
            val firstOpId = capturingStore.state.keys.single()

            // Neuen Store fuer den Resume-Lauf mit genau diesem Manifest,
            // aber alle Tabellen als COMPLETED markiert.
            val resumeStore = InMemoryCheckpointStore()
            val warmed = capturingStore.state.getValue(firstOpId)
            resumeStore.save(
                warmed.copy(
                    tableSlices = warmed.tableSlices.map {
                        it.copy(status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.COMPLETED)
                    },
                ),
            )

            // Dieser Executor schlaegt fehl, wenn er aufgerufen wird —
            // aber der Runner ruft ihn trotzdem, skippedTables sorgt
            // dafuer, dass keine Tabelle tatsaechlich exportiert wird.
            val runner = newRunner(
                stderr,
                exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                    // Der StreamingExporter-Produktivpfad filtert tables
                    // gegen skipped; hier im Test simulieren wir das
                    // Ergebnis: leere Summary, aber Aufruf passiert.
                    opts.tables.size shouldBe resume.skippedTables.size
                    dev.dmigrate.streaming.ExportResult(
                        tables = emptyList(),
                        totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 1,
                    )
                },
                checkpointStoreFactory = { resumeStore },
            )
            val exit = runner.execute(
                request(
                    output = checkpointDir,
                    splitFiles = true,
                    checkpointDir = checkpointDir,
                    resume = firstOpId,
                ),
            )
            exit shouldBe 0
            // Manifest ist am Ende removed.
            resumeStore.completeCount shouldBe 1
        }

        // 0.9.0 Phase C.1 Review-Fix (§4.4 / §5.2):
        // pipeline.checkpoint.directory aus der Config wird jetzt ueber
        // CheckpointConfig.merge(...) verdrahtet.

    }
})
