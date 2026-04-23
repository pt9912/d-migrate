package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.FailedFinishInfo
import dev.dmigrate.driver.data.TriggerMode
import io.kotest.core.spec.style.FunSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain as shouldContainInCollection
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection

/**
 * Unit-Tests für [DataImportRunner] mit Fakes für alle externen
 * Collaborators (targetResolver, URL-Parser, Pool-Factory,
 * WriterLookup, ImportExecutor).
 *
 * Deckt jeden Exit-Code-Pfad aus Plan §6.11 (0/1/2/3/4/5/7) ab.
 */
class DataImportRunnerTest : FunSpec({

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

    class FakeDataWriter(
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE,
    ) : DataWriter {
        override fun schemaSync(): SchemaSync =
            error("FakeDataWriter.schemaSync() must not be called")
        override fun openTable(
            pool: ConnectionPool,
            table: String,
            options: ImportOptions,
        ): TableImportSession =
            error("FakeDataWriter.openTable() must not be called — runner delegates to ImportExecutor")
    }

    val successExecutor: ImportExecutor = ImportExecutor { ctx, _, _, _ ->
        val tables = when (val input = ctx.input) {
            is dev.dmigrate.streaming.ImportInput.Stdin -> listOf(input.table)
            is dev.dmigrate.streaming.ImportInput.SingleFile -> listOf(input.table)
            is dev.dmigrate.streaming.ImportInput.Directory -> listOf("t1")
        }
        val summaries = tables.map {
            TableImportSummary(
                table = it, rowsInserted = 10, rowsUpdated = 0, rowsSkipped = 0,
                rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                triggerMode = TriggerMode.FIRE, durationMs = 3,
            )
        }
        ImportResult(
            tables = summaries,
            totalRowsInserted = 10L * tables.size,
            totalRowsUpdated = 0,
            totalRowsSkipped = 0,
            totalRowsUnknown = 0,
            totalRowsFailed = 0,
            durationMs = 3,
        )
    }

    // ─── Helpers ──────────────────────────────────────────────────

    /** Temporäre JSON-Datei für Tests die eine existierende Source brauchen. */
    val tempJsonFile: Path = Files.createTempFile("dmigrate-import-test-", ".json").also {
        Files.writeString(it, """[{"id":1}]""")
    }

    val tempDir: Path = Files.createTempDirectory("dmigrate-import-dir-test-")

    fun request(
        target: String? = "sqlite:///tmp/d-migrate-runner-fake.db",
        source: String = tempJsonFile.toString(),
        format: String? = null,
        schema: Path? = null,
        table: String? = "users",
        tables: List<String>? = null,
        onError: String = "abort",
        onConflict: String? = null,
        triggerMode: String = "fire",
        truncate: Boolean = false,
        disableFkChecks: Boolean = false,
        reseedSequences: Boolean = true,
        encoding: String? = null,
        csvNoHeader: Boolean = false,
        csvNullString: String = "",
        chunkSize: Int = 10_000,
        cliConfigPath: Path? = null,
        quiet: Boolean = false,
        noProgress: Boolean = false,
        resume: String? = null,
        checkpointDir: Path? = null,
    ) = DataImportRequest(
        target = target,
        source = source,
        format = format,
        schema = schema,
        table = table,
        tables = tables,
        onError = onError,
        onConflict = onConflict,
        triggerMode = triggerMode,
        truncate = truncate,
        disableFkChecks = disableFkChecks,
        reseedSequences = reseedSequences,
        encoding = encoding,
        csvNoHeader = csvNoHeader,
        csvNullString = csvNullString,
        chunkSize = chunkSize,
        cliConfigPath = cliConfigPath,
        quiet = quiet,
        noProgress = noProgress,
        resume = resume,
        checkpointDir = checkpointDir,
    )

    fun isolatedTargetResolver(target: String?, configPath: Path?): String {
        if (target != null) {
            val resolver = NamedConnectionResolver(
                configPathFromCli = configPath,
                envLookup = { null },
                defaultConfigPath = Path.of("/tmp/d-migrate-nonexistent-default-config.yaml"),
            )
            return resolver.resolve(target)
        }
        throw ConfigResolveException("--target was not provided and no default_target configured.")
    }

    class StderrCapture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined(): String = lines.joinToString("\n")
    }

    /** Prüft Exit-Code mit Debug-Output bei Fehlschlag. */
    fun assertExit(actual: Int, expected: Int, stderr: StderrCapture) {
        withClue("expected exit $expected but got $actual; stderr:\n${stderr.joined()}") {
            actual shouldBe expected
        }
    }

    fun newRunner(
        stderr: StderrCapture,
        targetResolver: (String?, Path?) -> String = ::isolatedTargetResolver,
        urlParser: (String) -> ConnectionConfig = ConnectionUrlParser::parse,
        poolFactory: (ConnectionConfig) -> ConnectionPool = { FakeConnectionPool() },
        writerLookup: (DatabaseDialect) -> DataWriter = { FakeDataWriter() },
        schemaPreflight: (Path, ImportInput, DataExportFormat) -> SchemaPreflightResult = { _, input, _ ->
            SchemaPreflightResult(input)
        },
        schemaTargetValidator: (schema: dev.dmigrate.core.model.SchemaDefinition, table: String, targetColumns: List<TargetColumn>) -> Unit =
            { _, _, _ -> },
        importExecutor: ImportExecutor = successExecutor,
        progressReporter: dev.dmigrate.streaming.ProgressReporter = dev.dmigrate.streaming.NoOpProgressReporter,
        stdinProvider: () -> java.io.InputStream = { ByteArrayInputStream("""[{"id":1}]""".toByteArray()) },
        checkpointStoreFactory: ((Path) -> dev.dmigrate.streaming.checkpoint.CheckpointStore)? = null,
        checkpointConfigResolver: (Path?) -> dev.dmigrate.streaming.CheckpointConfig? = { null },
        clock: () -> java.time.Instant = java.time.Instant::now,
    ): DataImportRunner = DataImportRunner(
        targetResolver = targetResolver,
        urlParser = urlParser,
        poolFactory = poolFactory,
        writerLookup = writerLookup,
        schemaPreflight = schemaPreflight,
        schemaTargetValidator = schemaTargetValidator,
        importExecutor = importExecutor,
        progressReporter = progressReporter,
        stdinProvider = stdinProvider,
        stderr = stderr.sink,
        checkpointStoreFactory = checkpointStoreFactory,
        checkpointConfigResolver = checkpointConfigResolver,
        clock = clock,
    )

    // ─── Happy path (Exit 0) ──────────────────────────────────────

    test("Exit 0: happy path with single file emits progress summary") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        assertExit(runner.execute(request()), 0, stderr)
        stderr.joined() shouldContain "Imported 1 table(s)"
    }

    test("Exit 0: pool.close() is called on happy path") {
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(stderr, poolFactory = { pool })
        assertExit(runner.execute(request()), 0, stderr)
        pool.closeCount shouldBe 1
    }

    test("Exit 0: format auto-detected from .json extension") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        // tempJsonFile has .json extension, format=null → auto-detect
        assertExit(runner.execute(request(format = null)), 0, stderr)
    }

    test("Exit 0: explicit --format overrides extension") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        assertExit(runner.execute(request(format = "json")), 0, stderr)
    }

    test("Exit 0: stdin source with explicit format and table") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = "-", format = "json", table = "users")) shouldBe 0
    }

    test("Exit 0: directory source without --table") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = tempDir.toString(), format = "json", table = null)) shouldBe 0
    }

    test("Exit 0: --quiet suppresses progress summary") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        assertExit(runner.execute(request(quiet = true)), 0, stderr)
        stderr.lines.shouldBeEmpty()
    }

    test("Exit 0: --no-progress suppresses progress summary") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        assertExit(runner.execute(request(noProgress = true)), 0, stderr)
        stderr.lines.shouldBeEmpty()
    }

    // ─── Exit 7: Config / URL / Registry ─────────────────────────

    test("Exit 7: ConfigResolveException maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            targetResolver = { _, configPath ->
                val resolver = NamedConnectionResolver(
                    configPathFromCli = configPath,
                    envLookup = { null },
                )
                resolver.resolve("staging")
            },
        )
        runner.execute(
            request(
                target = "staging",
                cliConfigPath = Path.of("/nope/missing-config.yaml"),
            )
        ) shouldBe 7
        stderr.joined() shouldContain "Config file not found"
    }

    test("Exit 7: urlParser IllegalArgumentException maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            urlParser = { throw IllegalArgumentException("Unknown dialect in URL") },
        )
        runner.execute(request()) shouldBe 7
        stderr.joined() shouldContain "Unknown dialect"
    }

    test("Exit 7: writer registry miss maps to exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            writerLookup = { d -> throw IllegalArgumentException("No DataWriter registered for dialect $d") },
        )
        assertExit(runner.execute(request()), 7, stderr)
        stderr.joined() shouldContain "No DataWriter registered"
    }

    test("Exit 2: missing target without default_target maps to CLI error") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            targetResolver = { _, _ ->
                throw CliUsageException("--target is required when database.default_target is not set.")
            },
        )
        assertExit(runner.execute(request(target = null)), 2, stderr)
        stderr.joined() shouldContain "--target is required"
    }

    // ─── Exit 2: CLI validation errors ──────────────────────────

    test("Exit 2: --table and --tables are mutually exclusive") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(table = "users", tables = listOf("orders"))) shouldBe 2
        stderr.joined() shouldContain "--table and --tables are mutually exclusive"
    }

    test("Exit 2: invalid --table identifier (whitespace)") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(table = "bad name")) shouldBe 2
        stderr.joined() shouldContain "not a valid identifier"
    }

    test("Exit 2: invalid --tables identifier") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(table = null, tables = listOf("ok", "bad name"))) shouldBe 2
        stderr.joined() shouldContain "not a valid identifier"
    }

    test("Exit 2: --truncate with explicit --on-conflict abort") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(truncate = true, onConflict = "abort")) shouldBe 2
        stderr.joined() shouldContain "--truncate"
        stderr.joined() shouldContain "--on-conflict abort"
    }

    test("Exit 2: --disable-fk-checks on PostgreSQL") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            targetResolver = { _, _ -> "postgresql://u:p@host/db" },
        )
        runner.execute(request(
            target = "postgresql://u:p@host/db",
            disableFkChecks = true,
        )) shouldBe 2
        stderr.joined() shouldContain "--disable-fk-checks"
        stderr.joined().lowercase() shouldContain "postgresql"
    }

    test("Exit 2: --trigger-mode disable on MySQL") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            targetResolver = { _, _ -> "mysql://u:p@host/db" },
        )
        runner.execute(request(
            target = "mysql://u:p@host/db",
            triggerMode = "disable",
        )) shouldBe 2
        stderr.joined() shouldContain "--trigger-mode disable"
        stderr.joined() shouldContain "MYSQL"
    }

    test("Exit 2: --trigger-mode disable on SQLite") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(triggerMode = "disable")) shouldBe 2
        stderr.joined() shouldContain "--trigger-mode disable"
        stderr.joined() shouldContain "SQLITE"
    }

    test("Exit 2: stdin without --format") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = "-", format = null, table = "users")) shouldBe 2
        stderr.joined() shouldContain "--format is required"
        stderr.joined() shouldContain "stdin"
    }

    test("Exit 2: file without recognizable extension and no --format") {
        val noExtFile = Files.createTempFile("dmigrate-noext-", "")
        Files.writeString(noExtFile, "data")
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = noExtFile.toString(), format = null)) shouldBe 2
        stderr.joined() shouldContain "Cannot detect format"
    }

    test("Exit 2: stdin without --table") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = "-", format = "json", table = null)) shouldBe 2
        stderr.joined() shouldContain "--table is required"
    }

    test("Exit 2: single file without --table") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(table = null)) shouldBe 2
        stderr.joined() shouldContain "--table is required"
    }

    test("Exit 2: directory source with --table is rejected") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = tempDir.toString(), format = "json", table = "users")) shouldBe 2
        stderr.joined() shouldContain "--table is only supported for stdin or single-file imports"
    }

    test("Exit 2: source path does not exist") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(source = "/nope/nonexistent.json")) shouldBe 2
        stderr.joined() shouldContain "Source path does not exist"
    }

    test("Exit 2: unknown encoding") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(encoding = "not-a-real-charset-ever")) shouldBe 2
        stderr.joined() shouldContain "Unknown encoding"
    }

    // ─── Exit 3: Pre-flight (schema mismatch, strict trigger) ───

    test("Exit 3: ImportSchemaMismatchException maps to exit 3") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw ImportSchemaMismatchException("column 'userId' has no exact match")
            },
        )
        assertExit(runner.execute(request()), 3, stderr)
        stderr.joined() shouldContain "userId"
    }

    test("Exit 3: invalid --schema fails before target resolution") {
        val schemaFile = Files.createTempFile("dmigrate-invalid-schema-", ".yaml")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "Broken"
            version: "1.0.0"
            tables:
              users: {}
            """.trimIndent()
        )
        var targetResolverInvoked = false
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            targetResolver = { _, _ ->
                targetResolverInvoked = true
                "sqlite:///tmp/should-not-be-used.db"
            },
            schemaPreflight = DataImportSchemaPreflight::prepare,
        )
        assertExit(runner.execute(request(schema = schemaFile)), 3, stderr)
        targetResolverInvoked shouldBe false
        stderr.joined() shouldContain "Schema validation failed"
        Files.deleteIfExists(schemaFile)
    }

    test("Exit 3: --schema detects circular directory dependencies before target resolution") {
        val importDir = Files.createTempDirectory("dmigrate-import-cycle-")
        Files.writeString(importDir.resolve("a.json"), """[{"id":1}]""")
        Files.writeString(importDir.resolve("b.json"), """[{"id":1}]""")
        val schemaFile = Files.createTempFile("dmigrate-cycle-schema-", ".yaml")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "Cycle"
            version: "1.0.0"
            tables:
              a:
                columns:
                  id:
                    type: identifier
                  b_id:
                    type: integer
                    references:
                      table: b
                      column: id
              b:
                columns:
                  id:
                    type: identifier
                  a_id:
                    type: integer
                    references:
                      table: a
                      column: id
            """.trimIndent()
        )
        var targetResolverInvoked = false
        var executorInvoked = false
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            targetResolver = { _, _ ->
                targetResolverInvoked = true
                "sqlite:///tmp/should-not-be-used.db"
            },
            schemaPreflight = DataImportSchemaPreflight::prepare,
            importExecutor = ImportExecutor { _, _, _, _ ->
                executorInvoked = true
                error("importExecutor must not be called when schema preflight fails")
            },
        )
        assertExit(
            runner.execute(
                request(
                    source = importDir.toString(),
                    format = "json",
                    schema = schemaFile,
                    table = null,
                )
            ),
            3,
            stderr,
        )
        targetResolverInvoked shouldBe false
        executorInvoked shouldBe false
        stderr.joined() shouldContain "dependency cycle"
        stderr.joined() shouldContain "a.b_id -> b.id"
        stderr.joined() shouldContain "b.a_id -> a.id"
        Files.deleteIfExists(importDir.resolve("a.json"))
        Files.deleteIfExists(importDir.resolve("b.json"))
        Files.deleteIfExists(schemaFile)
        Files.deleteIfExists(importDir)
    }

    // ─── Exit 4: Connection errors ──────────────────────────────

})
