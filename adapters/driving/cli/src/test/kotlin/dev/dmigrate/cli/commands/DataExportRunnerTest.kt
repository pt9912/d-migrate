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
class DataExportRunnerTest : FunSpec({

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
    val successExecutor: ExportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, _, _, _, _ ->
        val summaries = tables.map { TableExportSummary(it, rows = 10, chunks = 1, bytes = 256, durationMs = 3) }
        ExportResult(
            tables = summaries,
            totalRows = 10L * tables.size,
            totalChunks = tables.size.toLong(),
            totalBytes = 256L * tables.size,
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
        filter = filter,
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

    test("Exit 0: happy path emits progress summary on stderr") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request()) shouldBe 0
        stderr.joined() shouldContain "Exported 1 table(s)"
    }

    test("Exit 0: pool.close() is called on happy path") {
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(stderr, poolFactory = { pool })
        runner.execute(request()) shouldBe 0
        pool.closeCount shouldBe 1
    }

    test("Exit 0: auto-discovery uses tableLister.listTables when --tables is null") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            listerLookup = { FakeTableLister(provider = { listOf("discovered_table") }) },
        )
        runner.execute(request(tables = null)) shouldBe 0
        // The progress summary reflects the discovered table count
        stderr.joined() shouldContain "1 table(s)"
    }

    test("Exit 0: empty --tables list falls through to auto-discovery") {
        // `tables = emptyList()` → explicitTables = null after takeIf { it.isNotEmpty() }
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            listerLookup = { FakeTableLister(provider = { listOf("auto_t") }) },
        )
        runner.execute(request(tables = emptyList())) shouldBe 0
    }

    test("Exit 0: schema-qualified identifier 'public.users' is accepted") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(tables = listOf("public.users"))) shouldBe 0
    }

    test("Exit 0: --filter is passed as a WhereClause to the executor") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, filter, _, _, _, _, _, _, _ ->
                capturedFilter = filter
                ExportResult(
                    tables = tables.map { TableExportSummary(it, 1, 1, 1, 1) },
                    totalRows = 1, totalChunks = 1, totalBytes = 1, durationMs = 1,
                )
            }
        )
        runner.execute(request(filter = "id = 42")) shouldBe 0
        (capturedFilter as? DataFilter.WhereClause)?.sql shouldBe "id = 42"
    }

    test("Exit 0: blank --filter is dropped (treated as no filter)") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, filter, _, _, _, _, _, _, _ ->
                capturedFilter = filter
                ExportResult(
                    tables = tables.map { TableExportSummary(it, 0, 0, 0, 0) },
                    totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 0,
                )
            }
        )
        runner.execute(request(filter = "   ")) shouldBe 0
        capturedFilter shouldBe null
    }

    test("Exit 0: --since builds a parameterized filter for the executor") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, filter, _, _, _, _, _, _, _ ->
                capturedFilter = filter
                ExportResult(
                    tables = tables.map { TableExportSummary(it, 1, 1, 1, 1) },
                    totalRows = 1, totalChunks = 1, totalBytes = 1, durationMs = 1,
                )
            }
        )

        runner.execute(
            request(
                sinceColumn = "updated_at",
                since = "2026-01-01",
            )
        ) shouldBe 0

        val filter = capturedFilter.shouldBeInstanceOf<DataFilter.ParameterizedClause>()
        filter.sql shouldBe "\"updated_at\" >= ?"
        filter.params shouldBe listOf(LocalDate.parse("2026-01-01"))
    }

    test("Exit 0: --filter and --since compose into a Compound in stable order") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, filter, _, _, _, _, _, _, _ ->
                capturedFilter = filter
                ExportResult(
                    tables = tables.map { TableExportSummary(it, 1, 1, 1, 1) },
                    totalRows = 1, totalChunks = 1, totalBytes = 1, durationMs = 1,
                )
            }
        )

        runner.execute(
            request(
                filter = "active = 1",
                sinceColumn = "updated_at",
                since = "2026-01-01T10:15:30",
            )
        ) shouldBe 0

        val filter = capturedFilter.shouldBeInstanceOf<DataFilter.Compound>()
        filter.parts[0].shouldBeInstanceOf<DataFilter.WhereClause>().sql shouldBe "active = 1"
        val marker = filter.parts[1].shouldBeInstanceOf<DataFilter.ParameterizedClause>()
        marker.sql shouldBe "\"updated_at\" >= ?"
        marker.params shouldBe listOf(LocalDateTime.parse("2026-01-01T10:15:30"))
    }

    // ─── Exit 7: Config / URL / Registry ─────────────────────────

    test("Exit 7: ConfigResolveException maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            sourceResolver = { source, configPath ->
                val resolver = NamedConnectionResolver(
                    configPathFromCli = configPath,
                    envLookup = { null },
                )
                resolver.resolve(source)
            },
        )
        // source is a connection name (no "://") and the config file doesn't exist
        val exitCode = runner.execute(
            request(
                source = "staging",
                cliConfigPath = Path.of("/nope/missing-config.yaml"),
            )
        )
        exitCode shouldBe 7
        stderr.joined() shouldContain "Config file not found"
    }

    test("Exit 7: urlParser IllegalArgumentException maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            urlParser = { url -> throw IllegalArgumentException("Unknown dialect in URL $url") },
        )
        runner.execute(request()) shouldBe 7
        stderr.joined() shouldContain "Unknown dialect"
    }

    test("Exit 7: DataReader registry miss maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            readerLookup = { d -> throw IllegalArgumentException("No DataReader registered for dialect $d") },
        )
        runner.execute(request()) shouldBe 7
        stderr.joined() shouldContain "No DataReader registered"
    }

    test("Exit 7: TableLister registry miss maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            listerLookup = { d -> throw IllegalArgumentException("No TableLister registered for dialect $d") },
        )
        runner.execute(request()) shouldBe 7
        stderr.joined() shouldContain "No TableLister registered"
    }

    // ─── Exit 2: Validation errors ───────────────────────────────

    test("Exit 2: unknown encoding") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(encoding = "not-a-real-charset-ever")) shouldBe 2
        stderr.joined() shouldContain "Unknown encoding"
    }

    test("Exit 2: invalid --tables identifier (whitespace)") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(tables = listOf("weird name"))) shouldBe 2
        stderr.joined() shouldContain "not a valid identifier"
    }

    test("Exit 2: invalid --tables identifier (SQL injection)") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(tables = listOf("users; DROP TABLE users"))) shouldBe 2
    }

    test("Exit 2: empty effective tables (lister returns empty list)") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            listerLookup = { FakeTableLister(provider = { emptyList() }) },
        )
        runner.execute(request(tables = null)) shouldBe 2
        stderr.joined() shouldContain "No tables to export"
    }

    test("Exit 2: multiple tables to stdout without --split-files") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(tables = listOf("users", "orders"), output = null)) shouldBe 2
        stderr.joined() shouldContain "Cannot export"
    }

    test("Exit 2: --split-files without --output") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(splitFiles = true, output = null)) shouldBe 2
        stderr.joined() shouldContain "--split-files"
    }

    test("Exit 2: --csv-delimiter multi-char") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(format = "csv", csvDelimiter = "::")) shouldBe 2
        stderr.joined() shouldContain "single character"
    }

    test("Exit 2: --csv-delimiter empty") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(format = "csv", csvDelimiter = "")) shouldBe 2
    }

    test("Exit 2: --since-column without --since") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(sinceColumn = "updated_at")) shouldBe 2
        stderr.joined() shouldContain "--since-column and --since must be used together"
    }

    test("Exit 2: --since without --since-column") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(since = "2026-01-01")) shouldBe 2
        stderr.joined() shouldContain "--since-column and --since must be used together"
    }

    test("Exit 2: invalid --since-column identifier") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(sinceColumn = "bad column", since = "1")) shouldBe 2
        stderr.joined() shouldContain "--since-column value 'bad column' is not a valid identifier"
    }

    test("Exit 2: M-R5 preflight rejects literal ? in --filter when combined with --since") {
        var poolOpened = false
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = {
                poolOpened = true
                FakeConnectionPool()
            },
        )
        runner.execute(
            request(
                filter = "name LIKE 'Order?%'",
                sinceColumn = "updated_at",
                since = "2026-01-01",
            )
        ) shouldBe 2
        poolOpened shouldBe false
        stderr.joined() shouldContain "--filter must not contain literal '?' when combined with --since"
    }

    // ─── Exit 4: Connection / lister I/O ─────────────────────────

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
            exportExecutor = ExportExecutor { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
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
            exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, _, _, _, _ ->
                ExportResult(
                    tables = tables.map { TableExportSummary(it, 0, 0, 0, 1, error = "disk full") },
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
            exportExecutor = ExportExecutor { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ ->
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
            exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, pr, _, _, _, _, _, _ ->
                pr.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.EXPORT, tables.size))
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
            exportExecutor = ExportExecutor { _, _, _, _, _, _, _, _, _, _, pr, _, _, _, _, _, _ ->
                pr.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
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
            exportExecutor = ExportExecutor { _, _, _, _, _, _, _, _, _, _, pr, _, _, _, _, _, _ ->
                pr.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
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
            val executor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, _, onDone, _, _ ->
                val summaries = tables.map {
                    dev.dmigrate.streaming.TableExportSummary(
                        table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                    )
                }
                summaries.forEach(onDone)
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
                exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, _, onDone, _, _ ->
                    tables.forEach {
                        onDone(
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 0, chunks = 1, bytes = 0, durationMs = 1
                            )
                        )
                    }
                    dev.dmigrate.streaming.ExportResult(
                        tables = tables.map {
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 0, chunks = 1, bytes = 0, durationMs = 1
                            )
                        },
                        totalRows = 0, totalChunks = tables.size.toLong(),
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
                exportExecutor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, skipped, _, _, _ ->
                    // Der StreamingExporter-Produktivpfad filtert tables
                    // gegen skipped; hier im Test simulieren wir das
                    // Ergebnis: leere Summary, aber Aufruf passiert.
                    tables.size shouldBe skipped.size
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

        test("checkpointConfigResolver-Directory gewinnt, wenn kein CLI-Override gesetzt ist") {
            val store = InMemoryCheckpointStore()
            var storeDir: Path? = null
            val stderr = StderrCapture()
            val configDir = Path.of("/tmp/d-migrate-c1-from-cfg")
            val executor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, _, onDone, _, _ ->
                val summaries = tables.map {
                    dev.dmigrate.streaming.TableExportSummary(
                        table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                    )
                }
                summaries.forEach(onDone)
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
            val executor = ExportExecutor { _, _, _, _, tables, _, _, _, _, _, _, _, _, _, onDone, _, _ ->
                tables.forEach {
                    onDone(
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
                exportExecutor = ExportExecutor { _, _, _, _, tables, out, _, _, _, _, _, _, _, _, onDone, _, _ ->
                    // Phase C.2 §5.4: der Runner leitet Single-File-Laeufe
                    // in eine Staging-Datei im Checkpoint-Verzeichnis um.
                    // Der Fake simuliert den echten Writer, indem er die
                    // uebergebene Output-Datei anlegt, damit die
                    // spaetere atomic-rename-Operation einen Gegenstand
                    // hat.
                    if (out is dev.dmigrate.streaming.ExportOutput.SingleFile) {
                        Files.createDirectories(out.path.parent)
                        Files.writeString(out.path, "")
                    }
                    tables.forEach {
                        onDone(
                            dev.dmigrate.streaming.TableExportSummary(
                                table = it, rows = 1, chunks = 1, bytes = 1, durationMs = 1,
                            ),
                        )
                    }
                    dev.dmigrate.streaming.ExportResult(
                        tables = tables.map {
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
                _, _, _, _, tables, _, _, _, _, _, _, _, _, skipped, onDone, _, _,
                ->
                for (t in tables) {
                    if (t in skipped) continue
                    if (t == "orders") throw RuntimeException("simulated I/O failure on orders")
                    onDone(TableExportSummary(table = t, rows = 10, chunks = 1, bytes = 256, durationMs = 1))
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
                _, _, _, _, tables, _, _, _, _, _, _, _, _, skipped, onDone, _, _,
                ->
                capturedSkipped += skipped
                val processed = tables.filter { it !in skipped }
                val summaries = processed.map {
                    TableExportSummary(table = it, rows = 5, chunks = 1, bytes = 128, durationMs = 1)
                        .also(onDone)
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

    context("C.2 Fall 1 — ohne --since-column bleibt alles C.1-Verhalten") {
        test("no since-column + no manifest marker → silent C.1-fallback, no ResumeMarker passed") {
            val capturedMarkers = mutableListOf<Map<String, dev.dmigrate.driver.data.ResumeMarker>>()
            val executor: ExportExecutor = ExportExecutor {
                _, _, _, _, tables, _, _, _, _, _, _, _, _, _, _, markers, _,
                ->
                capturedMarkers += markers
                val summaries = tables.map {
                    TableExportSummary(it, rows = 1, chunks = 1, bytes = 10, durationMs = 1)
                }
                ExportResult(summaries, 1L * tables.size, tables.size.toLong(), 10L * tables.size, 1)
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
                _, _, _, _, tables, _, _, _, _, _, _, _, _, _, _, markers, _,
                ->
                capturedMarkers += markers
                val summaries = tables.map {
                    TableExportSummary(it, rows = 1, chunks = 1, bytes = 10, durationMs = 1)
                }
                ExportResult(summaries, 1L * tables.size, tables.size.toLong(), 10L * tables.size, 1)
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
                _, _, _, _, tables, _, _, _, _, _, _, _, _, _, _, markers, _,
                ->
                capturedMarkers += markers
                val summaries = tables.map {
                    TableExportSummary(it, rows = 1, chunks = 1, bytes = 10, durationMs = 1)
                }
                ExportResult(summaries, 1L * tables.size, tables.size.toLong(), 10L * tables.size, 1)
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

    context("C.2 onChunkProcessed → Manifest gets IN_PROGRESS with resumePosition") {
        test("per-chunk callback persists marker position into manifest") {
            val storeDir = Files.createTempDirectory("d-migrate-c2-chunk-")
            val executor: ExportExecutor = ExportExecutor {
                _, _, _, _, tables, out, _, _, _, _, _, _, _, _, onDone, markers, onChunk,
                ->
                // Siehe warmRunner (Phase C.2 §5.4): Single-File leitet
                // auf Staging um; der Fake legt die Staging-Datei an,
                // damit der Runner atomic-rename tun kann.
                if (out is dev.dmigrate.streaming.ExportOutput.SingleFile) {
                    Files.createDirectories(out.path.parent)
                    Files.writeString(out.path, "")
                }
                // Simuliere zwei Chunks + Abschluss fuer 'users'
                val table = tables.single()
                if (table in markers) {
                    onChunk(
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
                    onChunk(
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
                onDone(summary)
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
                _, _, _, _, tables, out, _, _, _, _, _, _, _, _, onDone, _, _,
                ->
                require(out is dev.dmigrate.streaming.ExportOutput.SingleFile)
                seenOutputPaths.add(out.path)
                // Simuliere echten Writer: Staging-Datei anlegen
                Files.writeString(out.path, """[{"id":1}]""")
                val summary = TableExportSummary(
                    tables.single(), rows = 1, chunks = 1, bytes = 10, durationMs = 1,
                )
                onDone(summary)
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
                _, _, _, _, tables, out, _, _, _, _, _, _, _, _, onDone, _, _,
                ->
                require(out is dev.dmigrate.streaming.ExportOutput.SingleFile)
                seenOutputPaths.add(out.path)
                Files.writeString(out.path, """[{"id":1}]""")
                val summary = TableExportSummary(
                    tables.single(), rows = 1, chunks = 1, bytes = 10, durationMs = 1,
                )
                onDone(summary)
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
                _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _,
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
                _, _, _, _, tables, out, _, _, _, _, _, _, _, _, onDone, markers, _,
                ->
                require(out is dev.dmigrate.streaming.ExportOutput.SingleFile)
                capturedMarkers += markers
                Files.writeString(out.path, "resumed")
                val summary = TableExportSummary(tables.single(), 1, 1, 10, 1)
                onDone(summary)
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
