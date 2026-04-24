package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportChunkCommit
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.TableChunkProgress
import dev.dmigrate.streaming.TableExportSummary
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Instant

class CheckpointWarningTest : FunSpec({

    class Capture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
    }

    class FakeCheckpointStore(
        private val manifest: CheckpointManifest? = null,
        private val loadFailure: CheckpointStoreException? = null,
        private val saveFailure: CheckpointStoreException? = null,
    ) : CheckpointStore {
        override fun load(operationId: String): CheckpointManifest? {
            if (loadFailure != null) throw loadFailure
            return manifest
        }

        override fun save(manifest: CheckpointManifest) {
            if (saveFailure != null) throw saveFailure
        }

        override fun list() = emptyList<dev.dmigrate.streaming.checkpoint.CheckpointReference>()

        override fun complete(operationId: String) = Unit
    }

    val fixedNow = Instant.parse("2026-04-24T08:00:00Z")

    fun exportRequest() = DataExportRequest(
        source = "sqlite:///tmp/test.db",
        format = "json",
        output = Path.of("/tmp/export.json"),
        tables = listOf("users"),
        filter = null,
        sinceColumn = null,
        since = null,
        encoding = "utf-8",
        chunkSize = 1000,
        splitFiles = false,
        csvDelimiter = ",",
        csvBom = false,
        csvNoHeader = false,
        nullString = "",
        cliConfigPath = null,
        quiet = true,
        noProgress = true,
        resume = null,
        checkpointDir = null,
    )

    fun importRequest() = DataImportRequest(
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
        chunkSize = 1000,
        cliConfigPath = null,
        quiet = true,
        noProgress = true,
        resume = null,
        checkpointDir = null,
    )

    fun exportManager(stderr: Capture) = ExportCheckpointManager(
        checkpointStoreFactory = null,
        checkpointConfigResolver = { null },
        resumeCoordinator = ExportResumeCoordinator(
            primaryKeyLookup = { _, _, _ -> emptyList() },
            stderr = stderr.sink,
        ),
        clock = { fixedNow },
        progressReporter = NoOpProgressReporter,
        stderr = stderr.sink,
    )

    fun importManager(stderr: Capture) = ImportCheckpointManager(
        checkpointStoreFactory = null,
        checkpointConfigResolver = { null },
        clock = { fixedNow },
        progressReporter = NoOpProgressReporter,
        stderr = stderr.sink,
    )

    test("export checkpoint callbacks warn once when metadata reload fails on resume") {
        val stderr = Capture()
        val manager = exportManager(stderr)
        val store = FakeCheckpointStore(
            loadFailure = CheckpointStoreException("metadata boom"),
        )

        manager.buildCallbacks(
            request = exportRequest(),
            resume = ExportResumeContext(
                operationId = "op-1",
                resuming = true,
                skippedTables = emptySet(),
                initialSlices = mapOf(
                    "users" to CheckpointTableSlice("users", CheckpointSliceStatus.IN_PROGRESS)
                ),
            ),
            store = store,
            fingerprint = "fp",
            tables = listOf("users"),
            markers = emptyMap(),
        )

        stderr.lines.count { it.contains("Failed to reload checkpoint metadata") } shouldBe 1
    }

    test("export checkpoint callbacks warn once when repeated manifest saves fail") {
        val stderr = Capture()
        val manager = exportManager(stderr)
        val store = FakeCheckpointStore(
            saveFailure = CheckpointStoreException("save boom"),
        )
        val callbacks = manager.buildCallbacks(
            request = exportRequest(),
            resume = ExportResumeContext(
                operationId = "op-2",
                resuming = false,
                skippedTables = emptySet(),
                initialSlices = mapOf(
                    "users" to CheckpointTableSlice("users", CheckpointSliceStatus.PENDING)
                ),
            ),
            store = store,
            fingerprint = "fp",
            tables = listOf("users"),
            markers = mapOf(
                "users" to ResumeMarker(
                    markerColumn = "id",
                    tieBreakerColumns = emptyList(),
                    position = null,
                )
            ),
        )

        callbacks.onChunkProcessed(
            TableChunkProgress(
                table = "users",
                rowsProcessed = 2,
                chunksProcessed = 1,
                position = ResumeMarker.Position(lastMarkerValue = 2, lastTieBreakerValues = emptyList()),
            )
        )
        callbacks.onChunkProcessed(
            TableChunkProgress(
                table = "users",
                rowsProcessed = 4,
                chunksProcessed = 2,
                position = ResumeMarker.Position(lastMarkerValue = 4, lastTieBreakerValues = emptyList()),
            )
        )
        callbacks.onTableCompleted(
            TableExportSummary(
                table = "users",
                rows = 4,
                chunks = 2,
                bytes = 64,
                durationMs = 1,
            )
        )

        stderr.lines.count { it.contains("Failed to update export checkpoint during the run") } shouldBe 1
    }

    test("import checkpoint callbacks warn once when metadata reload fails on resume") {
        val stderr = Capture()
        val manager = importManager(stderr)
        val store = FakeCheckpointStore(
            loadFailure = CheckpointStoreException("metadata boom"),
        )

        manager.buildCallbacks(
            request = importRequest(),
            format = DataExportFormat.JSON,
            resumeCtx = ImportResumeContext(
                operationId = "op-3",
                resuming = true,
                skippedTables = emptySet(),
                resumeStateByTable = emptyMap(),
                initialSlices = mapOf(
                    "users" to CheckpointTableSlice(
                        table = "users",
                        status = CheckpointSliceStatus.IN_PROGRESS,
                        inputFile = "/tmp/users.json",
                    )
                ),
            ),
            store = store,
            inputCtx = InputContext(
                effectiveTables = listOf("users"),
                inputFilesByTable = mapOf("users" to "/tmp/users.json"),
                fingerprint = "fp",
            ),
        )

        stderr.lines.count { it.contains("Failed to reload checkpoint metadata") } shouldBe 1
    }

    test("import checkpoint callbacks warn once when repeated manifest saves fail") {
        val stderr = Capture()
        val manager = importManager(stderr)
        val store = FakeCheckpointStore(
            saveFailure = CheckpointStoreException("save boom"),
        )
        val callbacks = manager.buildCallbacks(
            request = importRequest(),
            format = DataExportFormat.JSON,
            resumeCtx = ImportResumeContext(
                operationId = "op-4",
                resuming = false,
                skippedTables = emptySet(),
                resumeStateByTable = emptyMap(),
                initialSlices = mapOf(
                    "users" to CheckpointTableSlice(
                        table = "users",
                        status = CheckpointSliceStatus.PENDING,
                        inputFile = "/tmp/users.json",
                    )
                ),
            ),
            store = store,
            inputCtx = InputContext(
                effectiveTables = listOf("users"),
                inputFilesByTable = mapOf("users" to "/tmp/users.json"),
                fingerprint = "fp",
            ),
        )

        callbacks.onChunkCommitted(
            ImportChunkCommit(
                table = "users",
                chunkIndex = 0,
                chunksCommitted = 1,
                rowsInsertedTotal = 2,
                rowsUpdatedTotal = 0,
                rowsSkippedTotal = 0,
                rowsUnknownTotal = 0,
                rowsFailedTotal = 0,
            )
        )
        callbacks.onChunkCommitted(
            ImportChunkCommit(
                table = "users",
                chunkIndex = 1,
                chunksCommitted = 2,
                rowsInsertedTotal = 4,
                rowsUpdatedTotal = 0,
                rowsSkippedTotal = 0,
                rowsUnknownTotal = 0,
                rowsFailedTotal = 0,
            )
        )
        callbacks.onTableCompleted(
            TableImportSummary(
                table = "users",
                rowsInserted = 4,
                rowsUpdated = 0,
                rowsSkipped = 0,
                rowsUnknown = 0,
                rowsFailed = 0,
                chunkFailures = emptyList(),
                sequenceAdjustments = emptyList(),
                targetColumns = listOf(ColumnDescriptor("id", nullable = false, sqlTypeName = "INTEGER")),
                triggerMode = TriggerMode.FIRE,
                durationMs = 1,
            )
        )

        stderr.lines.count { it.contains("Failed to update import checkpoint during the run") } shouldBe 1
    }
})
