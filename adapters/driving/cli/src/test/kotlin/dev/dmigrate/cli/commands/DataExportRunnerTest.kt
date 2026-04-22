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

    test("Exit 0: --filter is parsed as DSL and passed as ParameterizedClause to the executor") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                capturedFilter = opts.filter
                ExportResult(
                    tables = opts.tables.map { TableExportSummary(it, 1, 1, 1, 1) },
                    totalRows = 1, totalChunks = 1, totalBytes = 1, durationMs = 1,
                )
            }
        )
        runner.execute(request(filter = "id = 42")) shouldBe 0
        val clause = capturedFilter.shouldBeInstanceOf<DataFilter.ParameterizedClause>()
        clause.params shouldBe listOf(42L)
    }

    test("Fingerprint stability: semantically equal filters with different whitespace/case produce same fingerprint") {
        // Two filter strings that differ only in whitespace and keyword case
        // must produce the same canonical form and therefore the same fingerprint.
        val filterA = parseFilter("status = 'OPEN'  AND  active = true")!!
        val filterB = parseFilter("status='OPEN' and active=TRUE")!!
        filterA.canonical shouldBe filterB.canonical

        // Verify through the full fingerprint computation path
        val fpA = ExportOptionsFingerprint.compute(ExportOptionsFingerprint.Input(
            format = "json", encoding = "utf-8", csvDelimiter = ",",
            csvBom = false, csvNoHeader = false, csvNullString = "",
            filter = filterA.canonical, sinceColumn = null, since = null,
            tables = listOf("users"), outputMode = "stdout", outputPath = "<stdout>",
        ))
        val fpB = ExportOptionsFingerprint.compute(ExportOptionsFingerprint.Input(
            format = "json", encoding = "utf-8", csvDelimiter = ",",
            csvBom = false, csvNoHeader = false, csvNullString = "",
            filter = filterB.canonical, sinceColumn = null, since = null,
            tables = listOf("users"), outputMode = "stdout", outputPath = "<stdout>",
        ))
        fpA shouldBe fpB
    }

    test("Fingerprint divergence: structurally different filters produce different fingerprints") {
        val filterA = parseFilter("status = 'OPEN'")!!
        val filterB = parseFilter("status = 'CLOSED'")!!
        filterA.canonical shouldNotBe filterB.canonical
    }

    test("Exit 0: null filter (no --filter flag) passes null to executor") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                capturedFilter = opts.filter
                ExportResult(
                    tables = opts.tables.map { TableExportSummary(it, 0, 0, 0, 0) },
                    totalRows = 0, totalChunks = 0, totalBytes = 0, durationMs = 0,
                )
            }
        )
        // null filter = --filter not provided (blank is rejected at CLI layer)
        runner.execute(request(filter = null)) shouldBe 0
        capturedFilter shouldBe null
    }

    test("Exit 0: --since builds a parameterized filter for the executor") {
        var capturedFilter: DataFilter? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                capturedFilter = opts.filter
                ExportResult(
                    tables = opts.tables.map { TableExportSummary(it, 1, 1, 1, 1) },
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
            exportExecutor = ExportExecutor { ctx, opts, resume, callbacks ->
                capturedFilter = opts.filter
                ExportResult(
                    tables = opts.tables.map { TableExportSummary(it, 1, 1, 1, 1) },
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
        val dslPart = filter.parts[0].shouldBeInstanceOf<DataFilter.ParameterizedClause>()
        dslPart.params shouldBe listOf(1L)
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

    test("Exit 2: invalid --filter DSL throws FilterParseException at request construction") {
        // Since 0.9.3, filter parsing happens in the CLI layer (DataExportCommand)
        // before DataExportRequest is constructed. The Runner never sees invalid DSL.
        // This test verifies that parseFilter rejects invalid input.
        shouldThrow<FilterParseException> {
            parseFilter("LIMIT 10")
        }
    }

    // ─── Exit 4: Connection / lister I/O ─────────────────────────

})
