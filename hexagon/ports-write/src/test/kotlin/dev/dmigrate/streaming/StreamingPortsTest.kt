package dev.dmigrate.streaming

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationDdlPayload
import dev.dmigrate.migration.MigrationIdentity
import dev.dmigrate.migration.MigrationRollback
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.MigrationVersionSource
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointReference
import dev.dmigrate.streaming.checkpoint.CheckpointResumePosition
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.UnsupportedCheckpointVersionException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class StreamingPortsTest : FunSpec({

    // ── ExportOutput ──────────────────────────────────────────────────

    test("ExportOutput.resolve returns Stdout for single table without output") {
        val result = ExportOutput.resolve(outputPath = null, splitFiles = false, tableCount = 1)
        result shouldBe ExportOutput.Stdout
    }

    test("ExportOutput.resolve throws for multiple tables to stdout") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = null, splitFiles = false, tableCount = 2)
        }.message!! shouldContain "Cannot export"
    }

    test("ExportOutput.resolve throws for split-files without output") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = null, splitFiles = true, tableCount = 1)
        }.message!! shouldContain "--split-files requires --output"
    }

    test("ExportOutput.resolve returns SingleFile for single table with file output") {
        val path = Path.of("/tmp/out.json")
        val result = ExportOutput.resolve(outputPath = path, splitFiles = false, tableCount = 1)
        result shouldBe ExportOutput.SingleFile(path)
    }

    test("ExportOutput.resolve throws for multiple tables to single file") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = Path.of("/tmp/out.json"), splitFiles = false, tableCount = 2)
        }.message!! shouldContain "Cannot export"
    }

    test("ExportOutput.resolve returns FilePerTable for split-files with non-existing directory") {
        val dir = Path.of("/tmp/non-existing-dir-${System.nanoTime()}")
        val result = ExportOutput.resolve(outputPath = dir, splitFiles = true, tableCount = 3)
        result shouldBe ExportOutput.FilePerTable(dir)
    }

    test("ExportOutput.resolve rejects tableCount 0") {
        shouldThrow<IllegalArgumentException> {
            ExportOutput.resolve(outputPath = null, splitFiles = false, tableCount = 0)
        }.message!! shouldContain "tableCount must be > 0"
    }

    test("ExportOutput.Stdout is a singleton object") {
        val a: ExportOutput = ExportOutput.Stdout
        val b: ExportOutput = ExportOutput.Stdout
        (a === b) shouldBe true
    }

    test("ExportOutput.SingleFile data class equality") {
        val a = ExportOutput.SingleFile(Path.of("/tmp/a.json"))
        val b = ExportOutput.SingleFile(Path.of("/tmp/a.json"))
        a shouldBe b
    }

    test("ExportOutput.FilePerTable data class equality") {
        val a = ExportOutput.FilePerTable(Path.of("/tmp/dir"))
        val b = ExportOutput.FilePerTable(Path.of("/tmp/dir"))
        a shouldBe b
    }

    test("ExportOutput.fileNameFor builds correct filenames") {
        ExportOutput.fileNameFor("public.users", DataExportFormat.JSON) shouldBe "public.users.json"
        ExportOutput.fileNameFor("orders", DataExportFormat.YAML) shouldBe "orders.yaml"
        ExportOutput.fileNameFor("items", DataExportFormat.CSV) shouldBe "items.csv"
    }

    // ── ImportInput ───────────────────────────────────────────────────

    test("ImportInput.Stdin carries table and stream") {
        val stream = ByteArrayInputStream(byteArrayOf())
        val input = ImportInput.Stdin(table = "users", input = stream)
        input.table shouldBe "users"
        input.input shouldBe stream
    }

    test("ImportInput.SingleFile carries table and path") {
        val path = Path.of("/tmp/users.json")
        val input = ImportInput.SingleFile(table = "users", path = path)
        input.table shouldBe "users"
        input.path shouldBe path
    }

    test("ImportInput.Directory carries path and optional filters") {
        val dir = ImportInput.Directory(path = Path.of("/tmp/data"))
        dir.tableFilter shouldBe null
        dir.tableOrder shouldBe null
    }

    test("ImportInput.Directory with filters") {
        val dir = ImportInput.Directory(
            path = Path.of("/tmp/data"),
            tableFilter = listOf("users", "orders"),
            tableOrder = listOf("users", "orders"),
        )
        dir.tableFilter shouldBe listOf("users", "orders")
        dir.tableOrder shouldBe listOf("users", "orders")
    }

    test("ImportInput.Stdin equality") {
        val stream = ByteArrayInputStream(byteArrayOf())
        val a = ImportInput.Stdin("t", stream)
        val b = ImportInput.Stdin("t", stream)
        a shouldBe b
    }

    test("ImportInput.SingleFile equality") {
        val a = ImportInput.SingleFile("t", Path.of("/tmp/a.json"))
        val b = ImportInput.SingleFile("t", Path.of("/tmp/a.json"))
        a shouldBe b
    }

    test("ImportInput.Directory copy") {
        val d = ImportInput.Directory(path = Path.of("/tmp/d"))
        val c = d.copy(tableFilter = listOf("x"))
        c.tableFilter shouldBe listOf("x")
    }

    // ── ProgressOperation enum ────────────────────────────────────────

    test("ProgressOperation has two values") {
        ProgressOperation.entries.map { it.name } shouldBe listOf("EXPORT", "IMPORT")
    }

    test("ProgressOperation valueOf round-trips") {
        ProgressOperation.valueOf("EXPORT") shouldBe ProgressOperation.EXPORT
        ProgressOperation.valueOf("IMPORT") shouldBe ProgressOperation.IMPORT
    }

    // ── TableProgressStatus enum ──────────────────────────────────────

    test("TableProgressStatus has two values") {
        TableProgressStatus.entries.map { it.name } shouldBe listOf("COMPLETED", "FAILED")
    }

    test("TableProgressStatus valueOf round-trips") {
        TableProgressStatus.valueOf("COMPLETED") shouldBe TableProgressStatus.COMPLETED
        TableProgressStatus.valueOf("FAILED") shouldBe TableProgressStatus.FAILED
    }

    // ── NoOpProgressReporter ──────────────────────────────────────────

    test("NoOpProgressReporter.report does nothing") {
        // Should not throw
        NoOpProgressReporter.report(
            ProgressEvent.RunStarted(ProgressOperation.EXPORT, totalTables = 1),
        )
    }

    // ── ProgressEvent sealed interface subtypes ───────────────────────

    test("ProgressEvent.RunStarted properties and defaults") {
        val event = ProgressEvent.RunStarted(
            operation = ProgressOperation.EXPORT,
            totalTables = 5,
        )
        event.operation shouldBe ProgressOperation.EXPORT
        event.totalTables shouldBe 5
        event.operationId shouldBe null
        event.resuming shouldBe false
    }

    test("ProgressEvent.RunStarted with all fields") {
        val event = ProgressEvent.RunStarted(
            operation = ProgressOperation.IMPORT,
            totalTables = 3,
            operationId = "op-42",
            resuming = true,
        )
        event.operationId shouldBe "op-42"
        event.resuming shouldBe true
    }

    test("ProgressEvent.ExportTableStarted properties") {
        val event = ProgressEvent.ExportTableStarted("users", 1, 3)
        event.table shouldBe "users"
        event.tableOrdinal shouldBe 1
        event.tableCount shouldBe 3
    }

    test("ProgressEvent.ExportChunkProcessed properties") {
        val event = ProgressEvent.ExportChunkProcessed(
            table = "users", tableOrdinal = 1, tableCount = 2,
            chunkIndex = 0, rowsInChunk = 100, rowsProcessed = 100, bytesWritten = 1024,
        )
        event.chunkIndex shouldBe 0
        event.rowsInChunk shouldBe 100
        event.rowsProcessed shouldBe 100
        event.bytesWritten shouldBe 1024
    }

    test("ProgressEvent.ExportTableFinished properties") {
        val event = ProgressEvent.ExportTableFinished(
            table = "users", tableOrdinal = 1, tableCount = 2,
            rowsProcessed = 500, chunksProcessed = 5, bytesWritten = 4096,
            durationMs = 1234, status = TableProgressStatus.COMPLETED,
        )
        event.rowsProcessed shouldBe 500
        event.status shouldBe TableProgressStatus.COMPLETED
    }

    test("ProgressEvent.ImportTableStarted properties") {
        val event = ProgressEvent.ImportTableStarted("orders", 2, 5)
        event.table shouldBe "orders"
        event.tableOrdinal shouldBe 2
        event.tableCount shouldBe 5
    }

    test("ProgressEvent.ImportChunkProcessed properties") {
        val event = ProgressEvent.ImportChunkProcessed(
            table = "orders", tableOrdinal = 1, tableCount = 1,
            chunkIndex = 3, rowsInChunk = 50, rowsProcessed = 200,
            rowsInserted = 45, rowsUpdated = 3, rowsSkipped = 2,
            rowsUnknown = 0, rowsFailed = 0,
        )
        event.rowsInserted shouldBe 45
        event.rowsUpdated shouldBe 3
        event.rowsSkipped shouldBe 2
        event.rowsUnknown shouldBe 0
        event.rowsFailed shouldBe 0
    }

    test("ProgressEvent.ImportTableFinished properties") {
        val event = ProgressEvent.ImportTableFinished(
            table = "orders", tableOrdinal = 1, tableCount = 1,
            rowsInserted = 100, rowsUpdated = 10, rowsSkipped = 5,
            rowsUnknown = 0, rowsFailed = 1, durationMs = 999,
            status = TableProgressStatus.FAILED,
        )
        event.rowsInserted shouldBe 100
        event.status shouldBe TableProgressStatus.FAILED
        event.durationMs shouldBe 999
    }

    test("ProgressEvent data class equality for RunStarted") {
        val a = ProgressEvent.RunStarted(ProgressOperation.EXPORT, 1)
        val b = ProgressEvent.RunStarted(ProgressOperation.EXPORT, 1)
        a shouldBe b
    }

    test("ProgressEvent data class copy for ExportTableStarted") {
        val e = ProgressEvent.ExportTableStarted("t", 1, 1)
        val c = e.copy(table = "u")
        c.table shouldBe "u"
    }

    // ── PipelineConfig ────────────────────────────────────────────────

    test("PipelineConfig defaults") {
        val config = PipelineConfig()
        config.chunkSize shouldBe 10_000
        config.checkpoint.enabled shouldBe false
    }

    test("PipelineConfig rejects non-positive chunkSize") {
        shouldThrow<IllegalArgumentException> { PipelineConfig(chunkSize = 0) }
        shouldThrow<IllegalArgumentException> { PipelineConfig(chunkSize = -1) }
    }

    test("PipelineConfig equality") {
        val a = PipelineConfig(chunkSize = 5000)
        val b = PipelineConfig(chunkSize = 5000)
        a shouldBe b
    }

    test("PipelineConfig copy") {
        val c = PipelineConfig().copy(chunkSize = 500)
        c.chunkSize shouldBe 500
    }

    // ── CheckpointConfig ──────────────────────────────────────────────

    test("CheckpointConfig defaults") {
        val config = CheckpointConfig()
        config.enabled shouldBe false
        config.rowInterval shouldBe CheckpointConfig.DEFAULT_ROW_INTERVAL
        config.maxInterval shouldBe CheckpointConfig.DEFAULT_MAX_INTERVAL
        config.directory shouldBe null
    }

    test("CheckpointConfig.DEFAULT_ROW_INTERVAL is 10000") {
        CheckpointConfig.DEFAULT_ROW_INTERVAL shouldBe 10_000L
    }

    test("CheckpointConfig.DEFAULT_MAX_INTERVAL is 5 minutes") {
        CheckpointConfig.DEFAULT_MAX_INTERVAL shouldBe Duration.ofMinutes(5)
    }

    test("CheckpointConfig rejects non-positive rowInterval") {
        shouldThrow<IllegalArgumentException> { CheckpointConfig(rowInterval = 0) }
        shouldThrow<IllegalArgumentException> { CheckpointConfig(rowInterval = -1) }
    }

    test("CheckpointConfig rejects zero maxInterval") {
        shouldThrow<IllegalArgumentException> { CheckpointConfig(maxInterval = Duration.ZERO) }
    }

    test("CheckpointConfig rejects negative maxInterval") {
        shouldThrow<IllegalArgumentException> { CheckpointConfig(maxInterval = Duration.ofSeconds(-1)) }
    }

    test("CheckpointConfig.merge with no arguments returns defaults") {
        val result = CheckpointConfig.merge()
        result shouldBe CheckpointConfig()
    }

    test("CheckpointConfig.merge CLI directory overrides config directory") {
        val config = CheckpointConfig(enabled = true, directory = Path.of("/config/dir"))
        val result = CheckpointConfig.merge(
            cliDirectory = Path.of("/cli/dir"),
            config = config,
        )
        result.directory shouldBe Path.of("/cli/dir")
        result.enabled shouldBe true
    }

    test("CheckpointConfig.merge config directory used when CLI is null") {
        val config = CheckpointConfig(enabled = true, directory = Path.of("/config/dir"))
        val result = CheckpointConfig.merge(cliDirectory = null, config = config)
        result.directory shouldBe Path.of("/config/dir")
    }

    test("CheckpointConfig.merge null config uses defaults") {
        val result = CheckpointConfig.merge(cliDirectory = Path.of("/cli/dir"), config = null)
        result.directory shouldBe Path.of("/cli/dir")
        result.enabled shouldBe false
    }

    test("CheckpointConfig equality") {
        val a = CheckpointConfig(enabled = true, rowInterval = 500)
        val b = CheckpointConfig(enabled = true, rowInterval = 500)
        a shouldBe b
    }

    test("CheckpointConfig copy") {
        val c = CheckpointConfig().copy(enabled = true)
        c.enabled shouldBe true
    }

    // ── ExportResult ──────────────────────────────────────────────────

    test("ExportResult.success is true when all tables have no error") {
        val result = ExportResult(
            tables = listOf(
                TableExportSummary("users", 100, 10, 1024, 500),
                TableExportSummary("orders", 200, 20, 2048, 600),
            ),
            totalRows = 300, totalChunks = 30, totalBytes = 3072, durationMs = 1100,
        )
        result.success shouldBe true
    }

    test("ExportResult.success is false when any table has error") {
        val result = ExportResult(
            tables = listOf(
                TableExportSummary("users", 100, 10, 1024, 500),
                TableExportSummary("orders", 0, 0, 0, 100, error = "fail"),
            ),
            totalRows = 100, totalChunks = 10, totalBytes = 1024, durationMs = 600,
        )
        result.success shouldBe false
    }

    test("ExportResult operationId defaults to null") {
        val result = ExportResult(emptyList(), 0, 0, 0, 0)
        result.operationId shouldBe null
    }

    test("ExportResult with operationId") {
        val result = ExportResult(emptyList(), 0, 0, 0, 0, operationId = "op-1")
        result.operationId shouldBe "op-1"
    }

    test("TableExportSummary data class equality") {
        val a = TableExportSummary("t", 1, 1, 1, 1)
        val b = TableExportSummary("t", 1, 1, 1, 1)
        a shouldBe b
    }

    test("TableExportSummary copy") {
        val s = TableExportSummary("t", 1, 1, 1, 1)
        val c = s.copy(error = "oops")
        c.error shouldBe "oops"
    }

    // ── TableChunkProgress ────────────────────────────────────────────

    test("TableChunkProgress properties") {
        val pos = dev.dmigrate.driver.data.ResumeMarker.Position(
            lastMarkerValue = "2026-01-01",
            lastTieBreakerValues = listOf(42L),
        )
        val tcp = TableChunkProgress("users", 100, 10, pos)
        tcp.table shouldBe "users"
        tcp.rowsProcessed shouldBe 100
        tcp.chunksProcessed shouldBe 10
        tcp.position shouldBe pos
    }

    test("TableChunkProgress equality") {
        val pos = dev.dmigrate.driver.data.ResumeMarker.Position("v", emptyList())
        val a = TableChunkProgress("t", 1, 1, pos)
        val b = TableChunkProgress("t", 1, 1, pos)
        a shouldBe b
    }

    // ── ImportResult ──────────────────────────────────────────────────

    test("ImportResult.success is true when all tables have no error and no failedFinish") {
        val summary = TableImportSummary(
            table = "users", rowsInserted = 10, rowsUpdated = 0, rowsSkipped = 0,
            rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
            sequenceAdjustments = emptyList(), targetColumns = emptyList(),
            triggerMode = TriggerMode.FIRE, durationMs = 100,
        )
        val result = ImportResult(
            tables = listOf(summary),
            totalRowsInserted = 10, totalRowsUpdated = 0, totalRowsSkipped = 0,
            totalRowsUnknown = 0, totalRowsFailed = 0, durationMs = 100,
        )
        result.success shouldBe true
    }

    test("ImportResult.success is false when table has error") {
        val summary = TableImportSummary(
            table = "users", rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0,
            rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
            sequenceAdjustments = emptyList(), targetColumns = emptyList(),
            triggerMode = TriggerMode.FIRE, error = "boom", durationMs = 50,
        )
        val result = ImportResult(
            listOf(summary), 0, 0, 0, 0, 0, 50,
        )
        result.success shouldBe false
    }

    test("ImportResult.success is false when table has failedFinish") {
        val fi = FailedFinishInfo(
            adjustments = emptyList(), causeMessage = "oops", causeClass = "RuntimeException",
        )
        val summary = TableImportSummary(
            table = "users", rowsInserted = 10, rowsUpdated = 0, rowsSkipped = 0,
            rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
            sequenceAdjustments = emptyList(), targetColumns = emptyList(),
            triggerMode = TriggerMode.FIRE, failedFinish = fi, durationMs = 50,
        )
        val result = ImportResult(
            listOf(summary), 10, 0, 0, 0, 0, 50,
        )
        result.success shouldBe false
    }

    test("ImportResult operationId defaults to null") {
        val result = ImportResult(emptyList(), 0, 0, 0, 0, 0, 0)
        result.operationId shouldBe null
    }

    // ── ChunkFailure ──────────────────────────────────────────────────

    test("ChunkFailure data class properties") {
        val cf = ChunkFailure("users", 3, 50, "timeout")
        cf.table shouldBe "users"
        cf.chunkIndex shouldBe 3
        cf.rowsLost shouldBe 50
        cf.reason shouldBe "timeout"
    }

    test("ChunkFailure equality") {
        val a = ChunkFailure("t", 1, 10, "r")
        val b = ChunkFailure("t", 1, 10, "r")
        a shouldBe b
    }

    // ── FailedFinishInfo ──────────────────────────────────────────────

    test("FailedFinishInfo data class properties and defaults") {
        val fi = FailedFinishInfo(
            adjustments = emptyList(),
            causeMessage = "fail",
            causeClass = "java.lang.RuntimeException",
        )
        fi.causeStack shouldBe null
        fi.closeCauseMessage shouldBe null
        fi.closeCauseClass shouldBe null
        fi.closeCauseStack shouldBe null
    }

    test("FailedFinishInfo with all optional fields") {
        val fi = FailedFinishInfo(
            adjustments = listOf(SequenceAdjustment("t", "id", "seq_id", 42)),
            causeMessage = "fail",
            causeClass = "RuntimeException",
            causeStack = "stack",
            closeCauseMessage = "close fail",
            closeCauseClass = "IOException",
            closeCauseStack = "close stack",
        )
        fi.causeStack shouldBe "stack"
        fi.closeCauseMessage shouldBe "close fail"
    }

    test("FailedFinishInfo equality") {
        val a = FailedFinishInfo(emptyList(), "m", "c")
        val b = FailedFinishInfo(emptyList(), "m", "c")
        a shouldBe b
    }

    // ── ImportTableResumeState ────────────────────────────────────────

    test("ImportTableResumeState properties") {
        val state = ImportTableResumeState(committedChunks = 5)
        state.committedChunks shouldBe 5
    }

    test("ImportTableResumeState rejects negative committedChunks") {
        shouldThrow<IllegalArgumentException> {
            ImportTableResumeState(committedChunks = -1)
        }
    }

    test("ImportTableResumeState allows zero committedChunks") {
        val state = ImportTableResumeState(committedChunks = 0)
        state.committedChunks shouldBe 0
    }

    test("ImportTableResumeState equality") {
        val a = ImportTableResumeState(3)
        val b = ImportTableResumeState(3)
        a shouldBe b
    }

    // ── ImportChunkCommit ─────────────────────────────────────────────

    test("ImportChunkCommit properties and rowsProcessedTotal") {
        val icc = ImportChunkCommit(
            table = "users", chunkIndex = 2, chunksCommitted = 3,
            rowsInsertedTotal = 100, rowsUpdatedTotal = 10, rowsSkippedTotal = 5,
            rowsUnknownTotal = 2, rowsFailedTotal = 1,
        )
        icc.table shouldBe "users"
        icc.chunkIndex shouldBe 2
        icc.chunksCommitted shouldBe 3
        icc.rowsProcessedTotal shouldBe 118 // 100+10+5+2+1
    }

})
