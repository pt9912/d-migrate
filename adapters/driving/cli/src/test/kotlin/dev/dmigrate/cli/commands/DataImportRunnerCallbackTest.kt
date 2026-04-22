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
class DataImportRunnerCallbackTest : FunSpec({

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


    context("D.3 chunk-commit / table-completed callback wiring") {
        test("fresh run routes skippedTables = empty + resumeStateByTable = empty") {
            val capturedSkipped = mutableListOf<Set<String>>()
            val capturedResumeStates =
                mutableListOf<Map<String, dev.dmigrate.streaming.ImportTableResumeState>>()
            val captureExecutor: ImportExecutor = ImportExecutor { ctx, _, resume, callbacks ->
                capturedSkipped += resume.skippedTables
                capturedResumeStates += resume.resumeStateByTable
                val table = when (val input = ctx.input) {
                    is dev.dmigrate.streaming.ImportInput.Stdin -> input.table
                    is dev.dmigrate.streaming.ImportInput.SingleFile -> input.table
                    is dev.dmigrate.streaming.ImportInput.Directory -> "t1"
                }
                val summary = TableImportSummary(
                    table = table, rowsInserted = 5, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ImportResult(
                    tables = listOf(summary), totalRowsInserted = 5, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0,
                    durationMs = 1,
                )
            }
            val stderr = StderrCapture()
            val runner = newRunner(stderr, importExecutor = captureExecutor)
            runner.execute(request()) shouldBe 0
            capturedSkipped.single() shouldBe emptySet<String>()
            capturedResumeStates.single() shouldBe emptyMap()
        }

        test("resume with IN_PROGRESS slice builds resumeStateByTable with chunksProcessed") {
            val storeDir = Files.createTempDirectory("d-migrate-d3-state-")
            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
            val opId = "d3-state"
            val fingerprint = ImportOptionsFingerprint.compute(
                ImportOptionsFingerprint.Input(
                    format = "json", encoding = null, csvNoHeader = false,
                    csvNullString = "", onError = "abort", onConflict = "abort",
                    triggerMode = "fire", truncate = false, disableFkChecks = false,
                    reseedSequences = true, chunkSize = 10_000,
                    tables = listOf("users"),
                    inputTopology = "single-file",
                    inputPath = tempJsonFile.toAbsolutePath().normalize().toString(),
                    targetDialect = "SQLITE",
                    targetUrl = "sqlite:///tmp/d-migrate-runner-fake.db",
                )
            )
            store.save(
                dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                    operationId = opId,
                    operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.IMPORT,
                    createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    format = "json", chunkSize = 10_000,
                    tableSlices = listOf(
                        dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                            table = "users",
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.IN_PROGRESS,
                            rowsProcessed = 500,
                            chunksProcessed = 5,
                        ),
                    ),
                    optionsFingerprint = fingerprint,
                ),
            )
            val capturedResumeStates =
                mutableListOf<Map<String, dev.dmigrate.streaming.ImportTableResumeState>>()
            val executor: ImportExecutor = ImportExecutor { ctx, _, resume, callbacks ->
                capturedResumeStates += resume.resumeStateByTable
                val table = (ctx.input as dev.dmigrate.streaming.ImportInput.SingleFile).table
                val summary = TableImportSummary(
                    table = table, rowsInserted = 1, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ImportResult(
                    tables = listOf(summary), totalRowsInserted = 1, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0,
                    durationMs = 1,
                )
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                importExecutor = executor,
                checkpointStoreFactory = { store },
            )
            runner.execute(
                request(resume = opId, checkpointDir = storeDir),
            ) shouldBe 0
            val states = capturedResumeStates.single()
            states.size shouldBe 1
            states.getValue("users").committedChunks shouldBe 5L
        }

        test("onChunkCommitted updates manifest with IN_PROGRESS status") {
            val storeDir = Files.createTempDirectory("d-migrate-d3-chunk-")
            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
            val executor: ImportExecutor = ImportExecutor { ctx, _, _, callbacks ->
                val table = (ctx.input as dev.dmigrate.streaming.ImportInput.SingleFile).table
                // Emit two chunk commits before completing
                callbacks.onChunkCommitted(
                    dev.dmigrate.streaming.ImportChunkCommit(
                        table = table, chunkIndex = 0, chunksCommitted = 1,
                        rowsInsertedTotal = 10, rowsUpdatedTotal = 0,
                        rowsSkippedTotal = 0, rowsUnknownTotal = 0, rowsFailedTotal = 0,
                    )
                )
                callbacks.onChunkCommitted(
                    dev.dmigrate.streaming.ImportChunkCommit(
                        table = table, chunkIndex = 1, chunksCommitted = 2,
                        rowsInsertedTotal = 20, rowsUpdatedTotal = 0,
                        rowsSkippedTotal = 0, rowsUnknownTotal = 0, rowsFailedTotal = 0,
                    )
                )
                val summary = TableImportSummary(
                    table = table, rowsInserted = 20, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ImportResult(
                    tables = listOf(summary), totalRowsInserted = 20, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0,
                    durationMs = 1,
                )
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                importExecutor = executor,
                checkpointStoreFactory = { store },
            )
            runner.execute(request(checkpointDir = storeDir)) shouldBe 0
            // After successful run, complete() removes the manifest.
            Files.list(storeDir).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".checkpoint.yaml") }
                .size shouldBe 0
            stderr.joined() shouldNotContain "Error:"
        }

        test("onTableCompleted with error maps slice to FAILED (not COMPLETED)") {
            val storeDir = Files.createTempDirectory("d-migrate-d3-failed-")
            val capturedOps = mutableListOf<dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus>()
            val sniffingStore = object : dev.dmigrate.streaming.checkpoint.CheckpointStore {
                val real = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
                override fun load(operationId: String) = real.load(operationId)
                override fun save(manifest: dev.dmigrate.streaming.checkpoint.CheckpointManifest) {
                    manifest.tableSlices.forEach { capturedOps += it.status }
                    real.save(manifest)
                }
                override fun list() = real.list()
                override fun complete(operationId: String) = real.complete(operationId)
            }
            val executor: ImportExecutor = ImportExecutor { ctx, _, _, callbacks ->
                val table = (ctx.input as dev.dmigrate.streaming.ImportInput.SingleFile).table
                val summary = TableImportSummary(
                    table = table, rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 5, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                    error = "duplicate key",
                )
                callbacks.onTableCompleted(summary)
                ImportResult(
                    tables = listOf(summary), totalRowsInserted = 0, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 5,
                    durationMs = 1,
                )
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                importExecutor = executor,
                checkpointStoreFactory = { sniffingStore },
            )
            runner.execute(request(checkpointDir = storeDir)) shouldBe 5
            // We should have seen FAILED recorded via onTableCompleted
            capturedOps shouldContainInCollection dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.FAILED
        }

        test("resume with COMPLETED slice routes skippedTables to executor") {
            val storeDir = Files.createTempDirectory("d-migrate-d3-skipped-")
            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
            val opId = "d3-skipped"
            val fingerprint = ImportOptionsFingerprint.compute(
                ImportOptionsFingerprint.Input(
                    format = "json", encoding = null, csvNoHeader = false,
                    csvNullString = "", onError = "abort", onConflict = "abort",
                    triggerMode = "fire", truncate = false, disableFkChecks = false,
                    reseedSequences = true, chunkSize = 10_000,
                    tables = listOf("users"),
                    inputTopology = "single-file",
                    inputPath = tempJsonFile.toAbsolutePath().normalize().toString(),
                    targetDialect = "SQLITE",
                    targetUrl = "sqlite:///tmp/d-migrate-runner-fake.db",
                )
            )
            store.save(
                dev.dmigrate.streaming.checkpoint.CheckpointManifest(
                    operationId = opId,
                    operationType = dev.dmigrate.streaming.checkpoint.CheckpointOperationType.IMPORT,
                    createdAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    updatedAt = java.time.Instant.parse("2026-04-16T10:00:00Z"),
                    format = "json", chunkSize = 10_000,
                    tableSlices = listOf(
                        dev.dmigrate.streaming.checkpoint.CheckpointTableSlice(
                            table = "users",
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.COMPLETED,
                        ),
                    ),
                    optionsFingerprint = fingerprint,
                ),
            )
            val capturedSkipped = mutableListOf<Set<String>>()
            val executor: ImportExecutor = ImportExecutor { _, _, resume, _ ->
                capturedSkipped += resume.skippedTables
                ImportResult(
                    tables = emptyList(), totalRowsInserted = 0, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0,
                    durationMs = 1,
                )
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                importExecutor = executor,
                checkpointStoreFactory = { store },
            )
            runner.execute(
                request(resume = opId, checkpointDir = storeDir),
            ) shouldBe 0
            capturedSkipped.single() shouldBe setOf("users")
        }

        test("E2: simulated import abort — resume continues from last committed chunk") {
            // Scenario: import of a single-file table where the executor
            // commits 2 chunks and then throws (simulated abort).
            // A second run with --resume must receive the resume state
            // indicating 2 committed chunks and complete successfully.
            val storeDir = Files.createTempDirectory("d-migrate-e2-import-abort-")
            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)

            // Run 1: commits 2 chunks then aborts.
            val run1Executor: ImportExecutor = ImportExecutor { ctx, _, _, callbacks ->
                val table = (ctx.input as dev.dmigrate.streaming.ImportInput.SingleFile).table
                callbacks.onChunkCommitted(
                    dev.dmigrate.streaming.ImportChunkCommit(
                        table = table, chunkIndex = 0, chunksCommitted = 1,
                        rowsInsertedTotal = 100, rowsUpdatedTotal = 0,
                        rowsSkippedTotal = 0, rowsUnknownTotal = 0, rowsFailedTotal = 0,
                    )
                )
                callbacks.onChunkCommitted(
                    dev.dmigrate.streaming.ImportChunkCommit(
                        table = table, chunkIndex = 1, chunksCommitted = 2,
                        rowsInsertedTotal = 200, rowsUpdatedTotal = 0,
                        rowsSkippedTotal = 0, rowsUnknownTotal = 0, rowsFailedTotal = 0,
                    )
                )
                throw RuntimeException("simulated connection loss after chunk 2")
            }
            val stderr1 = StderrCapture()
            val runner1 = newRunner(
                stderr1,
                importExecutor = run1Executor,
                checkpointStoreFactory = { store },
            )
            val exit1 = runner1.execute(request(checkpointDir = storeDir))
            exit1 shouldBe 5

            // Verify manifest: "users" should be IN_PROGRESS with 2 chunks committed.
            val manifests = Files.list(storeDir).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".checkpoint.yaml") }
            manifests.size shouldBe 1
            val opId = manifests.single().fileName.toString()
                .removeSuffix(".checkpoint.yaml")
            val savedManifest = store.load(opId)!!
            val slice = savedManifest.tableSlices.single()
            slice.table shouldBe "users"
            slice.status shouldBe dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.IN_PROGRESS
            slice.chunksProcessed shouldBe 2L

            // Run 2: resume with the stored manifest. Executor should
            // receive resumeStateByTable with committedChunks = 2.
            val capturedResumeStates =
                mutableListOf<Map<String, dev.dmigrate.streaming.ImportTableResumeState>>()
            val run2Executor: ImportExecutor = ImportExecutor { ctx, _, resume, callbacks ->
                capturedResumeStates += resume.resumeStateByTable
                val table = (ctx.input as dev.dmigrate.streaming.ImportInput.SingleFile).table
                val summary = TableImportSummary(
                    table = table, rowsInserted = 50, rowsUpdated = 0, rowsSkipped = 0,
                    rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(), targetColumns = emptyList(),
                    triggerMode = TriggerMode.FIRE, durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ImportResult(
                    tables = listOf(summary), totalRowsInserted = 50, totalRowsUpdated = 0,
                    totalRowsSkipped = 0, totalRowsUnknown = 0, totalRowsFailed = 0,
                    durationMs = 1,
                )
            }
            val stderr2 = StderrCapture()
            val runner2 = newRunner(
                stderr2,
                importExecutor = run2Executor,
                checkpointStoreFactory = { store },
            )
            val exit2 = runner2.execute(
                request(resume = opId, checkpointDir = storeDir),
            )
            exit2 shouldBe 0
            // Resume state should carry the 2 committed chunks
            val states = capturedResumeStates.single()
            states.size shouldBe 1
            states.getValue("users").committedChunks shouldBe 2L
            // After success, manifest should be removed via complete()
            Files.list(storeDir).use { it.toList() }
                .filter { it.fileName.toString().endsWith(".checkpoint.yaml") }
                .size shouldBe 0
        }
    }

    // ─── 0.9.0 Phase D.4: Directory-Topologie ───────────────────
    // (`docs/ImpPlan-0.9.0-D.md` §4.5 / §5.4)
    // ──────────────────────────────────────────────────────────────

})
