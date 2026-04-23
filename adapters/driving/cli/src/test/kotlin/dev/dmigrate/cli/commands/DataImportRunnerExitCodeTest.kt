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
class DataImportRunnerExitCodeTest : FunSpec({

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
        schemaTargetValidator:
            (
                schema: dev.dmigrate.core.model.SchemaDefinition,
                table: String,
                targetColumns: List<TargetColumn>,
            ) -> Unit =
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


    test("Exit 4: pool creation fails") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { throw RuntimeException("connection refused") },
        )
        assertExit(runner.execute(request()), 4, stderr)
        stderr.joined() shouldContain "Failed to connect to database"
        stderr.joined() shouldContain "connection refused"
    }

    test("Exit 4: pool.close() still runs when pool creation fails late") {
        // This tests a scenario where the pool opens but a later step fails.
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { pool },
            writerLookup = { throw IllegalArgumentException("no writer") },
        )
        assertExit(runner.execute(request()), 7, stderr)
        pool.closeCount shouldBe 1
    }

    // ─── Exit 5: Import / streaming errors ──────────────────────

    test("Exit 5: executor throws a generic Throwable") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw RuntimeException("streaming broke")
            },
        )
        assertExit(runner.execute(request()), 5, stderr)
        stderr.joined() shouldContain "Import failed"
        stderr.joined() shouldContain "streaming broke"
    }

    test("Exit 5: per-table summary has error → reported with table name") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            importExecutor = ImportExecutor { _, _, _, _ ->
                ImportResult(
                    tables = listOf(
                        TableImportSummary(
                            table = "users", rowsInserted = 0, rowsUpdated = 0,
                            rowsSkipped = 0, rowsUnknown = 0, rowsFailed = 5,
                            chunkFailures = emptyList(), sequenceAdjustments = emptyList(),
                            targetColumns = emptyList(), triggerMode = TriggerMode.FIRE,
                            error = "duplicate key", durationMs = 1,
                        )
                    ),
                    totalRowsInserted = 0, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0,
                    totalRowsFailed = 5, durationMs = 1,
                )
            },
        )
        assertExit(runner.execute(request()), 5, stderr)
        stderr.joined() shouldContain "Failed to import table 'users'"
        stderr.joined() shouldContain "duplicate key"
    }

    test("Exit 5: failedFinish path maps to exit 5 with manual-fix hint") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            importExecutor = ImportExecutor { _, _, _, _ ->
                ImportResult(
                    tables = listOf(
                        TableImportSummary(
                            table = "users", rowsInserted = 10, rowsUpdated = 0,
                            rowsSkipped = 0, rowsUnknown = 0, rowsFailed = 0,
                            chunkFailures = emptyList(), sequenceAdjustments = emptyList(),
                            targetColumns = emptyList(), triggerMode = TriggerMode.FIRE,
                            failedFinish = FailedFinishInfo(
                                adjustments = emptyList(),
                                causeMessage = "trigger re-enable failed",
                                causeClass = "SQLException",
                            ),
                            durationMs = 5,
                        )
                    ),
                    totalRowsInserted = 10, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0,
                    totalRowsFailed = 0, durationMs = 5,
                )
            },
        )
        assertExit(runner.execute(request()), 5, stderr)
        stderr.joined() shouldContain "finalization failed"
        stderr.joined() shouldContain "trigger re-enable failed"
        stderr.joined() shouldContain "manual post-import fix"
    }

    test("Exit 2: UnsupportedTriggerModeException maps to exit 2") {
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw UnsupportedTriggerModeException(
                    "--trigger-mode disable is not supported for dialect MYSQL"
                )
            },
        )
        assertExit(runner.execute(request()), 2, stderr)
        stderr.joined() shouldContain "--trigger-mode disable"
    }

    test("Exit 5: pool.close() still runs when executor throws") {
        val pool = FakeConnectionPool()
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            poolFactory = { pool },
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw RuntimeException("boom")
            },
        )
        assertExit(runner.execute(request()), 5, stderr)
        pool.closeCount shouldBe 1
    }

    test("Exit 0: --schema reorders directory imports topologically") {
        val importDir = Files.createTempDirectory("dmigrate-import-order-")
        Files.writeString(importDir.resolve("orders.json"), """[{"id":1,"user_id":1}]""")
        Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")
        val schemaFile = Files.createTempFile("dmigrate-order-schema-", ".yaml")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "Ordering"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
              orders:
                columns:
                  id:
                    type: identifier
                  user_id:
                    type: integer
                    references:
                      table: users
                      column: id
            """.trimIndent()
        )
        var seenInput: ImportInput? = null
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            schemaPreflight = DataImportSchemaPreflight::prepare,
            importExecutor = ImportExecutor { ctx, opts, resume, callbacks ->
                seenInput = ctx.input
                successExecutor.execute(
                    ctx, opts, resume,
                    callbacks.copy(onTableOpened = { _, _ -> }),
                )
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
            0,
            stderr,
        )
        (seenInput as ImportInput.Directory).tableOrder shouldBe listOf("users", "orders")
        Files.deleteIfExists(importDir.resolve("orders.json"))
        Files.deleteIfExists(importDir.resolve("users.json"))
        Files.deleteIfExists(schemaFile)
        Files.deleteIfExists(importDir)
    }

    test("Exit 3: --schema target validation runs after openTable and before writes") {
        val schemaFile = Files.createTempFile("dmigrate-target-schema-", ".yaml")
        Files.writeString(
            schemaFile,
            """
            schema_format: "1.0"
            name: "TargetCheck"
            version: "1.0.0"
            tables:
              users:
                columns:
                  id:
                    type: identifier
                  email:
                    type: email
                    required: true
            """.trimIndent()
        )
        var wrotePastOpen = false
        val stderr = StderrCapture()
        val runner = newRunner(
            stderr,
            schemaPreflight = DataImportSchemaPreflight::prepare,
            schemaTargetValidator = DataImportSchemaPreflight::validateTargetTable,
            importExecutor = ImportExecutor { ctx, _, _, callbacks ->
                val tableName = when (val input = ctx.input) {
                    is ImportInput.Stdin -> input.table
                    is ImportInput.SingleFile -> input.table
                    is ImportInput.Directory -> "users"
                }
                callbacks.onTableOpened(
                    tableName,
                    listOf(
                        TargetColumn("id", nullable = false, jdbcType = java.sql.Types.INTEGER, sqlTypeName = "INTEGER"),
                        TargetColumn("email", nullable = true, jdbcType = java.sql.Types.INTEGER, sqlTypeName = "INTEGER"),
                    ),
                )
                wrotePastOpen = true
                error("importExecutor must not continue after schema target mismatch")
            },
        )
        assertExit(runner.execute(request(schema = schemaFile)), 3, stderr)
        wrotePastOpen shouldBe false
        stderr.joined() shouldContain "does not match the provided --schema"
        stderr.joined() shouldContain "nullability mismatch"
        Files.deleteIfExists(schemaFile)
    }

    // ─── Pre-pool exits ─────────────────────────────────────────

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

    // ─── Progress summary formatting ────────────────────────────

    test("formatProgressSummary includes updates and sequences when present") {
        val result = ImportResult(
            tables = listOf(
                TableImportSummary(
                    table = "users", rowsInserted = 100, rowsUpdated = 20,
                    rowsSkipped = 0, rowsUnknown = 0, rowsFailed = 3,
                    chunkFailures = emptyList(),
                    sequenceAdjustments = listOf(
                        dev.dmigrate.driver.data.SequenceAdjustment("users", "id", "users_id_seq", 121)
                    ),
                    targetColumns = emptyList(), triggerMode = TriggerMode.FIRE, durationMs = 500,
                )
            ),
            totalRowsInserted = 100, totalRowsUpdated = 20,
            totalRowsSkipped = 0, totalRowsUnknown = 0,
            totalRowsFailed = 3, durationMs = 500,
        )
        val summary = DataImportRunner.formatProgressSummary(result)
        summary shouldContain "Imported 1 table(s)"
        summary shouldContain "100 inserted"
        summary shouldContain "20 updated"
        summary shouldContain "3 failed"
        summary shouldContain "in 0.5 s"
        summary shouldContain "reseeded 1 sequence(s)"
    }

    // ─── Progress Reporter Wiring (§8.3) ───────────────────────────

    test("default path passes reporter to executor") {
        val reporterEvents = mutableListOf<String>()
        val reporter = dev.dmigrate.streaming.ProgressReporter { reporterEvents += it::class.simpleName!! }
        val stderr = StderrCapture()
        val runner = newRunner(stderr, progressReporter = reporter,
            importExecutor = ImportExecutor { ctx, _, _, callbacks ->
                callbacks.progressReporter.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.IMPORT, 1))
                val tables = when (val input = ctx.input) {
                    is ImportInput.Stdin -> listOf(input.table)
                    is ImportInput.SingleFile -> listOf(input.table)
                    is ImportInput.Directory -> emptyList()
                }
                ImportResult(tables = emptyList(), totalRowsInserted = 0, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 0)
            })
        runner.execute(request())
        reporterEvents shouldContainExactly listOf("RunStarted")
    }

    test("--quiet suppresses reporter") {
        val reporterEvents = mutableListOf<String>()
        val reporter = dev.dmigrate.streaming.ProgressReporter { reporterEvents += it::class.simpleName!! }
        val stderr = StderrCapture()
        val runner = newRunner(stderr, progressReporter = reporter,
            importExecutor = ImportExecutor { _, _, _, callbacks ->
                callbacks.progressReporter.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.IMPORT, 1))
                ImportResult(tables = emptyList(), totalRowsInserted = 0, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 0)
            })
        runner.execute(request(quiet = true))
        reporterEvents.size shouldBe 0
    }

    test("--no-progress suppresses reporter") {
        val reporterEvents = mutableListOf<String>()
        val reporter = dev.dmigrate.streaming.ProgressReporter { reporterEvents += it::class.simpleName!! }
        val stderr = StderrCapture()
        val runner = newRunner(stderr, progressReporter = reporter,
            importExecutor = ImportExecutor { _, _, _, callbacks ->
                callbacks.progressReporter.report(dev.dmigrate.streaming.ProgressEvent.RunStarted(
                    dev.dmigrate.streaming.ProgressOperation.IMPORT, 1))
                ImportResult(tables = emptyList(), totalRowsInserted = 0, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 0)
            })
        runner.execute(request(noProgress = true))
        reporterEvents.size shouldBe 0
    }

    // ────────────────────────────────────────────────────────────
    // 0.9.0 Phase A / Phase D.1 (docs/ImpPlan-0.9.0-D.md §4.8):
    // Resume-CLI-Preflight fuer Import. Stdin-Quelle (`source == "-"`)
    // kann nicht wiederaufgenommen werden → Exit 2. Die Phase-A-
    // Warning („accepted but ignored") ist in Phase D.1 entfernt; der
    // Runner setzt den Resume-Vertrag jetzt aktiv um.
    // ────────────────────────────────────────────────────────────

    test("Phase A §4.4: --resume mit stdin-Quelle endet mit Exit 2") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        val exit = runner.execute(
            request(source = "-", resume = "./run.checkpoint.yaml"),
        )
        exit shouldBe 2
        stderr.joined() shouldContain "--resume"
        stderr.joined() shouldContain "stdin"
    }

    test("Phase D.1 §4.8: --resume ohne konfiguriertes Checkpoint-Verzeichnis endet mit Exit 7") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        val exit = runner.execute(request(resume = "some-op-id"))
        exit shouldBe 7
        stderr.joined() shouldContain "checkpoint directory"
    }

    test("Phase A: leeres --resume wird als abwesend behandelt (kein Warning, Happy-Path)") {
        val stderr = StderrCapture()
        val runner = newRunner(stderr)
        runner.execute(request(resume = ""))
        stderr.joined() shouldNotContain "Warning: --resume"
    }

    // ─── 0.9.0 Phase D.1: Resume-Preflight + Manifest-Lifecycle ──
    // (`docs/ImpPlan-0.9.0-D.md` §5.1)
    // ──────────────────────────────────────────────────────────────

})
