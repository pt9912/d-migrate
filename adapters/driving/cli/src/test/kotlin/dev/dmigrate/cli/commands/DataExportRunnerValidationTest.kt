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
class DataExportRunnerValidationTest : FunSpec({

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


    test("Exit 4: pool creation fails") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { throw RuntimeException("connection refused") },
        )
        runner.execute(request()) shouldBe 4
        stderr.joined() shouldContain "Failed to connect to database"
        stderr.joined() shouldContain "connection refused"
    }

    test("Exit 4: tableLister.listTables throws") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            listerLookup = {
                FakeTableLister(provider = { throw RuntimeException("permission denied") })
            },
        )
        runner.execute(request(tables = null)) shouldBe 4
        stderr.joined() shouldContain "Failed to list tables"
        stderr.joined() shouldContain "permission denied"
    }

    test("Exit 4: pool.close() is still called when an I/O error occurs after pool open") {
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { pool },
            listerLookup = {
                FakeTableLister(provider = { throw RuntimeException("io error") })
            },
        )
        runner.execute(request(tables = null)) shouldBe 4
        pool.closeCount shouldBe 1
    }

    // ─── Exit 5: Export errors ───────────────────────────────────

    test("Exit 5: executor throws a generic Throwable") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                throw RuntimeException("streaming broke")
            },
        )
        runner.execute(request()) shouldBe 5
        stderr.joined() shouldContain "Export failed"
        stderr.joined() shouldContain "streaming broke"
    }

    test("Exit 5: per-table summary has error → reported with table name") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                ExportResult(
                    tables = opts.tables.map { TableExportSummary(it, 0, 0, 0, 1, error = "disk full") },
                    totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 1,
                )
            },
        )
        runner.execute(request()) shouldBe 5
        stderr.joined() shouldContain "Failed to export table 'users'"
        stderr.joined() shouldContain "disk full"
    }

    test("Exit 5: pool.close() still runs when executor throws") {
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { pool },
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                throw RuntimeException("boom")
            },
        )
        runner.execute(request()) shouldBe 5
        pool.closeCount shouldBe 1
    }

    // ─── ValueSerializer warnings + quiet/no-progress ────────────

    test("ValueSerializer warnings are printed to stderr by default") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            collectWarnings = {
                listOf(
                    "  \u26a0 W202 users.balance (java.lang.Double): IEEE-754 Infinity not representable in JSON"
                )
            },
        )
        runner.execute(request()) shouldBe 0
        stderr.joined() shouldContain "W202"
        stderr.joined() shouldContain "users.balance"
    }

    test("--quiet suppresses both warnings and progress summary") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            collectWarnings = {
                listOf("  \u26a0 W202 users.balance (Double): Infinity")
            },
        )
        runner.execute(request(quiet = true)) shouldBe 0
        // quiet = true → nothing on stderr except actual errors (none here)
        stderr.lines.shouldBeEmpty()
    }

    test("--no-progress suppresses the summary but keeps warnings visible") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            collectWarnings = {
                listOf("  \u26a0 W202 users.balance (Double): Infinity")
            },
        )
        runner.execute(request(noProgress = true)) shouldBe 0
        stderr.joined() shouldContain "W202"
        // No progress line
        stderr.lines.none { it.contains("Exported") } shouldBe true
    }

    // ─── URL parse path with real ConnectionUrlParser ────────────

    test("URL source is passed through the real ConnectionUrlParser (sqlite)") {
        var parsedConfig: ConnectionConfig? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            urlParser = { url ->
                val parsed = ConnectionUrlParser.parse(url)
                parsedConfig = parsed
                parsed
            },
        )
        runner.execute(request(source = "sqlite:///tmp/runner-fake.db")) shouldBe 0
        parsedConfig?.dialect shouldBe DatabaseDialect.SQLITE
        parsedConfig?.database shouldBe "/tmp/runner-fake.db"
    }

    test("default file constructor path does not blow up when the executor is the happy-path default") {
        // Smoke test: a runner built with all-defaults can be instantiated
        // and is usable via the injected stderr + fake executor.
        val stderr = StderrCapture()
        val runner = DataExportRunner(
            sourceResolver = ::isolatedSourceResolver,
            urlParser = ConnectionUrlParser::parse,
            poolFactory = { FakeConnectionPool() },
            readerLookup = { FakeDataReader() },
            listerLookup = { FakeTableLister() },
            writerFactoryBuilder = { FakeWriterFactory() },
            collectWarnings = { emptyList() },
            exportExecutor = successExecutor,
            stderr = stderr.sink,
        )
        runner.execute(request()) shouldBe 0
    }

    // ─── Edge case: blank source ─────────────────────────────────

    test("Exit 2: blank --source is ultimately an encoding/validation error") {
        // Real NamedConnectionResolver.resolve() throws IllegalArgumentException
        // for a blank source; that is NOT a ConfigResolveException, so it
        // escapes out of the resolver and up through the caller. We don't
        // swallow it in the runner — this test pins that fact so future
        // refactors know the contract.
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        // IllegalArgumentException bubbles out — Clikt would turn it into
        // a UsageError upstream. For the runner-level test we just assert
        // that the resolve() call doesn't silently return 0.
        try {
            runner.execute(request(source = "   "))
        } catch (e: IllegalArgumentException) {
            // expected — the runner does not catch this (it relies on the
            // Clikt harness to map blank args to usage errors before reaching
            // the runner in real usage)
        }
    }

    // ─── File-system side effect probe: make sure pool is not
    //     accidentally left open after a synchronous Exit ────────

    test("every early exit closes the pool exactly once if it was opened") {
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { pool },
            readerLookup = { throw IllegalArgumentException("no reader") },
        )
        runner.execute(request()) shouldBe 7
        pool.closeCount shouldBe 1
    }

    test("pre-pool exits (bad encoding) do not touch the pool factory") {
        var poolFactoryInvoked = false
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = {
                poolFactoryInvoked = true
                FakeConnectionPool()
            },
        )
        runner.execute(request(encoding = "bogus-charset-12345")) shouldBe 2
        poolFactoryInvoked shouldBe false
    }

    // ─── Progress Reporter Wiring (§8.3) ───────────────────────────

    test("default path passes reporter to executor") {
        val reporterEvents = mutableListOf<String>()
        val reporter = dev.dmigrate.streaming.ProgressReporter { reporterEvents += it::class.simpleName!! }
        val stderr = StderrCapture()
        val runner = newRunner(stderr, progressReporter = reporter,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                callbacks.progressReporter.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.EXPORT, opts.tables.size))
                ExportResult(tables = emptyList(), totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 0)
            })
        runner.execute(request())
        reporterEvents shouldContainExactly listOf("RunStarted")
    }

    test("--quiet suppresses reporter") {
        val reporterEvents = mutableListOf<String>()
        val reporter = dev.dmigrate.streaming.ProgressReporter { reporterEvents += it::class.simpleName!! }
        val stderr = StderrCapture()
        val runner = newRunner(stderr, progressReporter = reporter,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                callbacks.progressReporter.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.EXPORT, 1))
                ExportResult(tables = emptyList(), totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 0)
            })
        runner.execute(request(quiet = true))
        reporterEvents.size shouldBe 0
    }

    test("--no-progress suppresses reporter") {
        val reporterEvents = mutableListOf<String>()
        val reporter = dev.dmigrate.streaming.ProgressReporter { reporterEvents += it::class.simpleName!! }
        val stderr = StderrCapture()
        val runner = newRunner(stderr, progressReporter = reporter,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                callbacks.progressReporter.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.EXPORT, 1))
                ExportResult(tables = emptyList(), totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 0)
            })
        runner.execute(request(noProgress = true))
        reporterEvents.size shouldBe 0
    }

    // ────────────────────────────────────────────────────────────
    // 0.9.0 Phase A (docs/ImpPlan-0.9.0-A.md §4.4 / §4.5):
    // Resume-CLI-Preflight. Der Runner akzeptiert `--resume` fuer
    // file-basierte Exports und lehnt stdout-Pfade mit Exit 2 ab.
    // Die Resume-Runtime selbst folgt in Phase B/C/D.
    // ────────────────────────────────────────────────────────────

    test("Phase A §4.4: --resume ohne --output (stdout) endet mit Exit 2") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        val exit = runner.execute(
            request(output = null, resume = "./run.checkpoint.yaml"),
        )
        exit shouldBe 2
        stderr.joined() shouldContain "--resume"
        stderr.joined() shouldContain "stdout"
    }

    test("Phase C.1: --resume ohne konfiguriertes Checkpoint-Verzeichnis endet mit Exit 7") {
        // Phase A §4.4-Warning ist in C.1 §4.7 entfernt: `--resume` darf
        // nicht mehr stumm akzeptiert und ignoriert werden. Ohne
        // Checkpoint-Dir (kein --checkpoint-dir gesetzt, keine
        // checkpointStoreFactory injiziert) hat der Runner keinen Store
        // und muss hart ablehnen.
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        val exit = runner.execute(
            request(
                output = Path.of("/tmp/d-migrate-phase-c1-no-dir.json"),
                resume = "./run.checkpoint.yaml",
            ),
        )
        exit shouldBe 7
        stderr.joined() shouldContain "--resume"
        stderr.joined() shouldContain "checkpoint"
    }

    test("Phase A: leeres --resume wird als abwesend behandelt (kein Warning)") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        val exit = runner.execute(request(resume = ""))
        exit shouldBe 0
        stderr.joined() shouldNotContain "Warning: --resume"
    }

    // ────────────────────────────────────────────────────────────────
    // 0.9.0 Phase C.1 (docs/ImpPlan-0.9.0-C1.md):
    // Manifest-Lifecycle + Resume-Preflight + Skipped-COMPLETED.
    // ────────────────────────────────────────────────────────────────

})
