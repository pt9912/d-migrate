package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ProgressEvent
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointReference
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.time.Instant

class ImportExecutionPlannerTest : FunSpec({

    fun request(
        checkpointDir: Path? = null,
        quiet: Boolean = false,
        noProgress: Boolean = false,
    ) = DataImportRequest(
        target = "sqlite:///tmp/test.db",
        source = "/tmp/users.json",
        format = "json",
        schema = null,
        table = "users",
        tables = null,
        onError = "abort",
        onConflict = null,
        triggerMode = "fire",
        truncate = false,
        disableFkChecks = false,
        reseedSequences = true,
        encoding = null,
        csvNoHeader = false,
        csvNullString = "",
        chunkSize = 10_000,
        cliConfigPath = null,
        quiet = quiet,
        noProgress = noProgress,
        checkpointDir = checkpointDir,
    )

    fun planner(
        stderr: MutableList<String>,
        writerLookup: (DatabaseDialect) -> DataWriter = { fakeWriter(it) },
        checkpointStoreFactory: ((Path) -> CheckpointStore)? = null,
        checkpointConfigResolver: (Path?) -> CheckpointConfig? = { null },
        progressReporter: ProgressReporter = ProgressReporter { },
    ) = ImportExecutionPlanner(
        preflightValidator = ImportPreflightValidator(
            writerLookup = writerLookup,
            schemaTargetValidator = { _, _, _ -> },
            stderr = stderr::add,
        ),
        checkpointManager = ImportCheckpointManager(
            checkpointStoreFactory = checkpointStoreFactory,
            checkpointConfigResolver = checkpointConfigResolver,
            clock = { Instant.parse("2026-04-23T12:00:00Z") },
            progressReporter = progressReporter,
            stderr = stderr::add,
        ),
    )

    fun connectionConfig() = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = "/tmp/test.db",
        user = null,
        password = null,
    )

    fun preparedImport() = SchemaPreflightResult(
        input = ImportInput.SingleFile("users", Path.of("/tmp/users.json")),
    )

    test("prepare builds execution plan for happy path") {
        val stderr = mutableListOf<String>()
        val progressEvents = mutableListOf<ProgressEvent>()
        val planner = planner(
            stderr = stderr,
            progressReporter = ProgressReporter { progressEvents += it },
        )

        val result = planner.prepare(
            request = request(),
            connectionConfig = connectionConfig(),
            resolvedUrl = "sqlite:///tmp/test.db",
            charset = null,
            format = DataExportFormat.JSON,
            preparedImport = preparedImport(),
        )

        val plan = (result as ImportExecutionPlanResult.Ok).value
        plan.checkpointStore shouldBe null
        plan.resumeContext.resuming shouldBe false
        plan.options.pipelineConfig.chunkSize shouldBe 10_000

        plan.callbacks.progressReporter.report(
            ProgressEvent.RunStarted(
                operation = dev.dmigrate.streaming.ProgressOperation.IMPORT,
                totalTables = 1,
            )
        )
        progressEvents.size shouldBe 1
        stderr shouldBe emptyList()
    }

    test("prepare returns exit 7 when writer lookup fails") {
        val stderr = mutableListOf<String>()
        val planner = planner(
            stderr = stderr,
            writerLookup = { throw IllegalArgumentException("No writer for dialect") },
        )

        val result = planner.prepare(
            request = request(),
            connectionConfig = connectionConfig(),
            resolvedUrl = "sqlite:///tmp/test.db",
            charset = null,
            format = DataExportFormat.JSON,
            preparedImport = preparedImport(),
        )

        result shouldBe ImportExecutionPlanResult.Exit(7)
        stderr.single() shouldContain "No writer for dialect"
    }

    test("prepare returns exit 7 when checkpoint manifest initialization fails") {
        val stderr = mutableListOf<String>()
        val planner = planner(
            stderr = stderr,
            checkpointStoreFactory = {
                object : CheckpointStore {
                    override fun load(operationId: String): CheckpointManifest? = null

                    override fun save(manifest: CheckpointManifest) {
                        throw CheckpointStoreException("disk full")
                    }

                    override fun list(): List<CheckpointReference> = emptyList()

                    override fun complete(operationId: String) = Unit
                }
            },
        )

        val result = planner.prepare(
            request = request(checkpointDir = Path.of("/tmp/checkpoints")),
            connectionConfig = connectionConfig(),
            resolvedUrl = "sqlite:///tmp/test.db",
            charset = null,
            format = DataExportFormat.JSON,
            preparedImport = preparedImport(),
        )

        result shouldBe ImportExecutionPlanResult.Exit(7)
        stderr.single() shouldContain "Failed to initialize checkpoint"
    }
})

private fun fakeWriter(dialect: DatabaseDialect) = object : DataWriter {
    override val dialect: DatabaseDialect = dialect

    override fun schemaSync(): SchemaSync = error("unused in planner tests")

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession = error("unused in planner tests")
}
