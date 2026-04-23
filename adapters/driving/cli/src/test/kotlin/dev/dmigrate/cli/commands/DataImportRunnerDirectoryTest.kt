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
class DataImportRunnerDirectoryTest : FunSpec({

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


    context("D.4 Directory topology") {
        test("fresh directory run populates inputFile + scanned tables in manifest") {
            val storeDir = Files.createTempDirectory("d-migrate-d4-fresh-")
            val importDir = Files.createTempDirectory("d-migrate-d4-in-")
            Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")
            Files.writeString(importDir.resolve("orders.json"), """[{"id":10}]""")
            val captured = mutableListOf<dev.dmigrate.streaming.checkpoint.CheckpointManifest>()
            val sniffingStore = object : dev.dmigrate.streaming.checkpoint.CheckpointStore {
                val real = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
                override fun load(operationId: String) = real.load(operationId)
                override fun save(manifest: dev.dmigrate.streaming.checkpoint.CheckpointManifest) {
                    captured += manifest
                    real.save(manifest)
                }
                override fun list() = real.list()
                override fun complete(operationId: String) = real.complete(operationId)
            }
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { sniffingStore },
            )
            runner.execute(
                request(
                    source = importDir.toString(),
                    format = "json",
                    table = null, // directory source
                    checkpointDir = storeDir,
                ),
            ) shouldBe 0
            // initial manifest has both tables (sorted) with correct inputFile
            val initial = captured.first()
            initial.tableSlices.map { it.table } shouldContainExactly listOf("orders", "users")
            initial.tableSlices.first { it.table == "users" }.inputFile shouldBe "users.json"
            initial.tableSlices.first { it.table == "orders" }.inputFile shouldBe "orders.json"
        }

        test("resume with renamed file → Exit 3 (fingerprint mismatch via inputFilesByTable)") {
            val storeDir = Files.createTempDirectory("d-migrate-d4-renamed-")
            val importDir = Files.createTempDirectory("d-migrate-d4-rename-in-")
            Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")

            // Warm manifest pretending the prior run had a file called `users_old.json`
            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
            val opId = "d4-renamed"
            val oldFingerprint = ImportOptionsFingerprint.compute(
                ImportOptionsFingerprint.Input(
                    format = "json", encoding = null, csvNoHeader = false,
                    csvNullString = "", onError = "abort", onConflict = "abort",
                    triggerMode = "fire", truncate = false, disableFkChecks = false,
                    reseedSequences = true, chunkSize = 10_000,
                    tables = listOf("users"),
                    inputTopology = "directory",
                    inputPath = importDir.toAbsolutePath().normalize().toString(),
                    targetDialect = "SQLITE",
                    targetUrl = "sqlite:///tmp/d-migrate-runner-fake.db",
                    inputFilesByTable = mapOf("users" to "users_old.json"),
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
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.PENDING,
                            inputFile = "users_old.json",
                        ),
                    ),
                    optionsFingerprint = oldFingerprint,
                ),
            )
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { store },
            )
            val exit = runner.execute(
                request(
                    source = importDir.toString(),
                    format = "json",
                    table = null,
                    resume = opId,
                    checkpointDir = storeDir,
                ),
            )
            // Fingerprint mismatch is the primary check; file-set
            // divergence shows up there (mapping changed from
            // users_old.json to users.json).
            exit shouldBe 3
            stderr.joined() shouldContain "fingerprint mismatch"
        }

        test("resume with added file → Exit 3 (tables list diverged)") {
            val storeDir = Files.createTempDirectory("d-migrate-d4-added-")
            val importDir = Files.createTempDirectory("d-migrate-d4-add-in-")
            Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")
            Files.writeString(importDir.resolve("orders.json"), """[{"id":10}]""") // NEW
            // Manifest only knows `users`; current directory has both.
            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
            val opId = "d4-added"
            val oldFingerprint = ImportOptionsFingerprint.compute(
                ImportOptionsFingerprint.Input(
                    format = "json", encoding = null, csvNoHeader = false,
                    csvNullString = "", onError = "abort", onConflict = "abort",
                    triggerMode = "fire", truncate = false, disableFkChecks = false,
                    reseedSequences = true, chunkSize = 10_000,
                    tables = listOf("users"),
                    inputTopology = "directory",
                    inputPath = importDir.toAbsolutePath().normalize().toString(),
                    targetDialect = "SQLITE",
                    targetUrl = "sqlite:///tmp/d-migrate-runner-fake.db",
                    inputFilesByTable = mapOf("users" to "users.json"),
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
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.PENDING,
                            inputFile = "users.json",
                        ),
                    ),
                    optionsFingerprint = oldFingerprint,
                ),
            )
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { store },
            )
            val exit = runner.execute(
                request(
                    source = importDir.toString(),
                    format = "json",
                    table = null,
                    resume = opId,
                    checkpointDir = storeDir,
                ),
            )
            exit shouldBe 3
            // Tables list divergence caught either by fingerprint or by the explicit tables check.
        }

        test("resume with same directory succeeds and runs import") {
            val storeDir = Files.createTempDirectory("d-migrate-d4-same-")
            val importDir = Files.createTempDirectory("d-migrate-d4-same-in-")
            Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")

            val store = dev.dmigrate.streaming.checkpoint.FileCheckpointStore(storeDir)
            val opId = "d4-same"
            val fp = ImportOptionsFingerprint.compute(
                ImportOptionsFingerprint.Input(
                    format = "json", encoding = null, csvNoHeader = false,
                    csvNullString = "", onError = "abort", onConflict = "abort",
                    triggerMode = "fire", truncate = false, disableFkChecks = false,
                    reseedSequences = true, chunkSize = 10_000,
                    tables = listOf("users"),
                    inputTopology = "directory",
                    inputPath = importDir.toAbsolutePath().normalize().toString(),
                    targetDialect = "SQLITE",
                    targetUrl = "sqlite:///tmp/d-migrate-runner-fake.db",
                    inputFilesByTable = mapOf("users" to "users.json"),
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
                            status = dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus.PENDING,
                            inputFile = "users.json",
                        ),
                    ),
                    optionsFingerprint = fp,
                ),
            )
            val stderr = StderrCapture()
            val runner = newRunner(
                stderr,
                checkpointStoreFactory = { store },
            )
            runner.execute(
                request(
                    source = importDir.toString(),
                    format = "json",
                    table = null,
                    resume = opId,
                    checkpointDir = storeDir,
                ),
            ) shouldBe 0
        }
    }

    // Ensure the temp path referenced in other tests never accidentally exists
    Files.deleteIfExists(Path.of("/tmp/d-migrate-nonexistent-default-config.yaml"))

    afterSpec {
        Files.deleteIfExists(tempJsonFile)
    }
})
