package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.streaming.ImportChunkCommit
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.PipelineConfig
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase E0.3 propagation guard: [ImportStreamingInvoker] must place the caller's
 * cancel token onto the [ImportExecutionContext] handed to the [ImportExecutor]
 * lambda. Phase E0.5 will use the same field at the chunk-loop boundary.
 */
class ImportStreamingInvokerCancelPropagationTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = error("unused")
        override fun activeConnections() = 0
        override fun close() = Unit
    }

    fun executionPlan() = ImportExecutionPlan(
        options = ImportPreparedOptions(
            importOptions = ImportOptions(triggerMode = TriggerMode.FIRE),
            formatReadOptions = FormatReadOptions(),
            pipelineConfig = PipelineConfig(chunkSize = 1_000),
            onTableOpened = { _: String, _: List<TargetColumn> -> },
        ),
        checkpointStore = null,
        resumeContext = ImportResumeContext(
            operationId = "op-1",
            resuming = false,
            skippedTables = emptySet(),
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

    test("token passed to invoker.execute lands on ImportExecutionContext") {
        val captured = AtomicReference<CancellationToken?>(null)
        val invoker = ImportStreamingInvoker(
            importExecutor = ImportExecutor { context, _, _, _ ->
                captured.set(context.cancellationToken)
                ImportResult(
                    tables = emptyList(),
                    totalRowsInserted = 0,
                    totalRowsUpdated = 0,
                    totalRowsSkipped = 0,
                    totalRowsUnknown = 0,
                    totalRowsFailed = 0,
                    durationMs = 0,
                    operationId = "op-1",
                )
            },
            stderr = { },
        )

        val token = CancellationTokenSource.create().token
        invoker.execute(
            format = DataExportFormat.JSON,
            pool = pool,
            preparedImport = preparedImport(),
            executionPlan = executionPlan(),
            cancellationToken = token,
        )

        (captured.get() === token) shouldBe true
    }

    test("default cancellationToken is none() when caller omits it") {
        val captured = AtomicReference<CancellationToken?>(null)
        val invoker = ImportStreamingInvoker(
            importExecutor = ImportExecutor { context, _, _, _ ->
                captured.set(context.cancellationToken)
                ImportResult(
                    tables = emptyList(),
                    totalRowsInserted = 0,
                    totalRowsUpdated = 0,
                    totalRowsSkipped = 0,
                    totalRowsUnknown = 0,
                    totalRowsFailed = 0,
                    durationMs = 0,
                    operationId = "op-1",
                )
            },
            stderr = { },
        )

        invoker.execute(
            format = DataExportFormat.JSON,
            pool = pool,
            preparedImport = preparedImport(),
            executionPlan = executionPlan(),
        )

        captured.get()!!.isCancellationRequested shouldBe false
    }
})
