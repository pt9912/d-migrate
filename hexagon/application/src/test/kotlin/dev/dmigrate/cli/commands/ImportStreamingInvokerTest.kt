package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.streaming.ImportChunkCommit
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.sql.Connection

class ImportStreamingInvokerTest : FunSpec({

    class FakeConnectionPool : ConnectionPool {
        override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

        override fun borrow(): Connection = error("unused in invoker tests")

        override fun activeConnections(): Int = 0

        override fun close() = Unit
    }

    fun executionPlan(
        operationId: String = "import-123",
        resuming: Boolean = false,
        skippedTables: Set<String> = emptySet(),
    ) = ImportExecutionPlan(
        options = ImportPreparedOptions(
            importOptions = ImportOptions(triggerMode = TriggerMode.FIRE),
            formatReadOptions = FormatReadOptions(),
            pipelineConfig = PipelineConfig(chunkSize = 1_000),
            onTableOpened = { _: String, _: List<TargetColumn> -> },
        ),
        checkpointStore = null,
        resumeContext = ImportResumeContext(
            operationId = operationId,
            resuming = resuming,
            skippedTables = skippedTables,
            resumeStateByTable = emptyMap(),
            initialSlices = emptyMap<String, CheckpointTableSlice>(),
        ),
        callbacks = ImportCallbacks(
            progressReporter = ProgressReporter { },
            onTableOpened = { _, _ -> },
            onChunkCommitted = { _: ImportChunkCommit -> },
            onTableCompleted = { },
        ),
    )

    fun preparedImport() = SchemaPreflightResult(
        input = ImportInput.SingleFile("users", Path.of("/tmp/users.json")),
    )

    fun successResult() = ImportResult(
        tables = listOf(
            TableImportSummary(
                table = "users",
                rowsInserted = 1,
                rowsUpdated = 0,
                rowsSkipped = 0,
                rowsUnknown = 0,
                rowsFailed = 0,
                chunkFailures = emptyList(),
                sequenceAdjustments = emptyList(),
                targetColumns = emptyList(),
                triggerMode = TriggerMode.FIRE,
                durationMs = 5,
            )
        ),
        totalRowsInserted = 1,
        totalRowsUpdated = 0,
        totalRowsSkipped = 0,
        totalRowsUnknown = 0,
        totalRowsFailed = 0,
        durationMs = 5,
    )

    test("execute forwards streaming inputs and preserves operation id") {
        val stderr = mutableListOf<String>()
        lateinit var capturedContext: ImportExecutionContext
        lateinit var capturedOptions: ImportExecutionOptions
        lateinit var capturedResume: ImportResumeState
        lateinit var capturedCallbacks: ImportCallbacks
        val invoker = ImportStreamingInvoker(
            importExecutor = ImportExecutor { context, options, resume, callbacks ->
                capturedContext = context
                capturedOptions = options
                capturedResume = resume
                capturedCallbacks = callbacks
                successResult()
            },
            stderr = stderr::add,
        )
        val pool = FakeConnectionPool()
        val executionPlan = executionPlan(
            operationId = "resume-42",
            resuming = true,
            skippedTables = setOf("audit_log"),
        )

        val result = invoker.execute(
            format = DataExportFormat.JSON,
            pool = pool,
            preparedImport = preparedImport(),
            executionPlan = executionPlan,
        )

        val importResult = (result as StreamingResult.Ok).value
        importResult.operationId shouldBe "resume-42"
        capturedContext.pool shouldBe pool
        capturedContext.input shouldBe preparedImport().input
        capturedOptions.format shouldBe DataExportFormat.JSON
        capturedOptions.config.chunkSize shouldBe 1_000
        capturedResume shouldBe ImportResumeState(
            operationId = "resume-42",
            resuming = true,
            skippedTables = setOf("audit_log"),
            resumeStateByTable = emptyMap(),
        )
        capturedCallbacks.progressReporter shouldBe executionPlan.callbacks.progressReporter
        stderr shouldBe emptyList()
    }

    test("execute maps UnsupportedTriggerModeException to exit 2") {
        val stderr = mutableListOf<String>()
        val invoker = ImportStreamingInvoker(
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw UnsupportedTriggerModeException("disable is not supported")
            },
            stderr = stderr::add,
        )

        val result = invoker.execute(
            format = DataExportFormat.JSON,
            pool = FakeConnectionPool(),
            preparedImport = preparedImport(),
            executionPlan = executionPlan(),
        )

        result shouldBe StreamingResult.Exit(2)
        stderr.single() shouldContain "disable"
    }

    test("execute maps schema mismatch to exit 3") {
        val stderr = mutableListOf<String>()
        val invoker = ImportStreamingInvoker(
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw ImportSchemaMismatchException("column 'user_id' missing")
            },
            stderr = stderr::add,
        )

        val result = invoker.execute(
            format = DataExportFormat.JSON,
            pool = FakeConnectionPool(),
            preparedImport = preparedImport(),
            executionPlan = executionPlan(),
        )

        result shouldBe StreamingResult.Exit(3)
        stderr.single() shouldContain "column 'user_id' missing"
    }

    test("execute maps unexpected failure to exit 5") {
        val stderr = mutableListOf<String>()
        val invoker = ImportStreamingInvoker(
            importExecutor = ImportExecutor { _, _, _, _ ->
                throw IllegalStateException("broken stream")
            },
            stderr = stderr::add,
        )

        val result = invoker.execute(
            format = DataExportFormat.JSON,
            pool = FakeConnectionPool(),
            preparedImport = preparedImport(),
            executionPlan = executionPlan(),
        )

        result shouldBe StreamingResult.Exit(5)
        stderr.single() shouldContain "Import failed: broken stream"
    }
})
