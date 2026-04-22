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

class StreamingPortsTestPart2 : FunSpec({

    // ── ExportOutput ──────────────────────────────────────────────────


    test("ImportChunkCommit rejects blank table") {
        shouldThrow<IllegalArgumentException> {
            ImportChunkCommit("", 0, 1, 0, 0, 0, 0, 0)
        }
    }

    test("ImportChunkCommit rejects chunksCommitted < 1") {
        shouldThrow<IllegalArgumentException> {
            ImportChunkCommit("t", 0, 0, 0, 0, 0, 0, 0)
        }
    }

    test("ImportChunkCommit equality") {
        val a = ImportChunkCommit("t", 0, 1, 10, 0, 0, 0, 0)
        val b = ImportChunkCommit("t", 0, 1, 10, 0, 0, 0, 0)
        a shouldBe b
    }

    // ── FinishTableResult ─────────────────────────────────────────────

    test("FinishTableResult.Success properties") {
        val adj = listOf(SequenceAdjustment("t", "id", "seq", 42))
        val result = FinishTableResult.Success(adjustments = adj)
        result.adjustments shouldBe adj
    }

    test("FinishTableResult.PartialFailure properties") {
        val cause = RuntimeException("oops")
        val result = FinishTableResult.PartialFailure(
            adjustments = emptyList(), cause = cause,
        )
        result.cause shouldBe cause
        result.adjustments shouldBe emptyList()
    }

    test("FinishTableResult.Success equality") {
        val a = FinishTableResult.Success(emptyList())
        val b = FinishTableResult.Success(emptyList())
        a shouldBe b
    }

    test("FinishTableResult subtypes are distinct") {
        val s: FinishTableResult = FinishTableResult.Success(emptyList())
        val f: FinishTableResult = FinishTableResult.PartialFailure(emptyList(), RuntimeException("x"))
        (s is FinishTableResult.Success) shouldBe true
        (f is FinishTableResult.PartialFailure) shouldBe true
    }

    // ── TriggerMode enum ──────────────────────────────────────────────

    test("TriggerMode has three values") {
        TriggerMode.entries.map { it.name } shouldBe listOf("FIRE", "DISABLE", "STRICT")
    }

    test("TriggerMode valueOf round-trips") {
        TriggerMode.valueOf("FIRE") shouldBe TriggerMode.FIRE
        TriggerMode.valueOf("DISABLE") shouldBe TriggerMode.DISABLE
        TriggerMode.valueOf("STRICT") shouldBe TriggerMode.STRICT
    }

    // ── OnConflict enum ───────────────────────────────────────────────

    test("OnConflict has three values") {
        OnConflict.entries.map { it.name } shouldBe listOf("ABORT", "SKIP", "UPDATE")
    }

    test("OnConflict valueOf round-trips") {
        OnConflict.valueOf("ABORT") shouldBe OnConflict.ABORT
        OnConflict.valueOf("SKIP") shouldBe OnConflict.SKIP
        OnConflict.valueOf("UPDATE") shouldBe OnConflict.UPDATE
    }

    // ── OnError enum ──────────────────────────────────────────────────

    test("OnError has three values") {
        OnError.entries.map { it.name } shouldBe listOf("ABORT", "SKIP", "LOG")
    }

    test("OnError valueOf round-trips") {
        OnError.valueOf("ABORT") shouldBe OnError.ABORT
        OnError.valueOf("SKIP") shouldBe OnError.SKIP
        OnError.valueOf("LOG") shouldBe OnError.LOG
    }

    // ── CheckpointOperationType enum ──────────────────────────────────

    test("CheckpointOperationType has two values") {
        CheckpointOperationType.entries.map { it.name } shouldBe listOf("EXPORT", "IMPORT")
    }

    // ── CheckpointSliceStatus enum ────────────────────────────────────

    test("CheckpointSliceStatus has four values") {
        CheckpointSliceStatus.entries.map { it.name } shouldBe
            listOf("PENDING", "IN_PROGRESS", "COMPLETED", "FAILED")
    }

    // ── CheckpointResumePosition ──────────────────────────────────────

    test("CheckpointResumePosition properties") {
        val pos = CheckpointResumePosition(
            markerColumn = "updated_at",
            markerValue = "2026-01-01",
            tieBreakerColumns = listOf("id"),
            tieBreakerValues = listOf("42"),
        )
        pos.markerColumn shouldBe "updated_at"
        pos.markerValue shouldBe "2026-01-01"
        pos.tieBreakerColumns shouldBe listOf("id")
        pos.tieBreakerValues shouldBe listOf("42")
    }

    test("CheckpointResumePosition rejects blank markerColumn") {
        shouldThrow<IllegalArgumentException> {
            CheckpointResumePosition("", null, emptyList(), emptyList())
        }
    }

    test("CheckpointResumePosition rejects mismatched tie-breaker sizes") {
        shouldThrow<IllegalArgumentException> {
            CheckpointResumePosition("col", "v", listOf("a", "b"), listOf("1"))
        }
    }

    test("CheckpointResumePosition rejects blank tie-breaker column") {
        shouldThrow<IllegalArgumentException> {
            CheckpointResumePosition("col", "v", listOf("a", " "), listOf("1", "2"))
        }
    }

    test("CheckpointResumePosition allows null markerValue") {
        val pos = CheckpointResumePosition("col", null, emptyList(), emptyList())
        pos.markerValue shouldBe null
    }

    test("CheckpointResumePosition allows null tie-breaker values") {
        val pos = CheckpointResumePosition("col", "v", listOf("a"), listOf(null))
        pos.tieBreakerValues shouldBe listOf(null)
    }

    test("CheckpointResumePosition equality") {
        val a = CheckpointResumePosition("c", "v", listOf("a"), listOf("1"))
        val b = CheckpointResumePosition("c", "v", listOf("a"), listOf("1"))
        a shouldBe b
    }

    // ── CheckpointTableSlice (beyond existing tests) ──────────────────

    test("CheckpointTableSlice with resumePosition and inputFile") {
        val pos = CheckpointResumePosition("col", "v", emptyList(), emptyList())
        val slice = CheckpointTableSlice(
            table = "users",
            status = CheckpointSliceStatus.IN_PROGRESS,
            rowsProcessed = 100,
            chunksProcessed = 10,
            resumePosition = pos,
            inputFile = "users.json",
        )
        slice.resumePosition shouldBe pos
        slice.inputFile shouldBe "users.json"
    }

    test("CheckpointTableSlice defaults") {
        val slice = CheckpointTableSlice(table = "t", status = CheckpointSliceStatus.PENDING)
        slice.rowsProcessed shouldBe 0
        slice.chunksProcessed shouldBe 0
        slice.lastMarker shouldBe null
        slice.resumePosition shouldBe null
        slice.inputFile shouldBe null
    }

    test("CheckpointTableSlice equality") {
        val a = CheckpointTableSlice("t", CheckpointSliceStatus.PENDING)
        val b = CheckpointTableSlice("t", CheckpointSliceStatus.PENDING)
        a shouldBe b
    }

    test("CheckpointTableSlice copy") {
        val s = CheckpointTableSlice("t", CheckpointSliceStatus.PENDING)
        val c = s.copy(status = CheckpointSliceStatus.COMPLETED)
        c.status shouldBe CheckpointSliceStatus.COMPLETED
    }

    // ── CheckpointReference ───────────────────────────────────────────

    test("CheckpointReference properties") {
        val ref = CheckpointReference("op-1", CheckpointOperationType.EXPORT, 1)
        ref.operationId shouldBe "op-1"
        ref.operationType shouldBe CheckpointOperationType.EXPORT
        ref.schemaVersion shouldBe 1
    }

    test("CheckpointReference equality") {
        val a = CheckpointReference("op-1", CheckpointOperationType.EXPORT, 1)
        val b = CheckpointReference("op-1", CheckpointOperationType.EXPORT, 1)
        a shouldBe b
    }

    // ── CheckpointStoreException ──────────────────────────────────────

    test("CheckpointStoreException carries message and cause") {
        val cause = RuntimeException("root")
        val ex = CheckpointStoreException("could not read", cause)
        ex.message shouldBe "could not read"
        ex.cause shouldBe cause
    }

    test("CheckpointStoreException with null cause") {
        val ex = CheckpointStoreException("error")
        ex.cause shouldBe null
    }

    // ── UnsupportedCheckpointVersionException ─────────────────────────

    test("UnsupportedCheckpointVersionException carries found version") {
        val ex = UnsupportedCheckpointVersionException(foundVersion = 99)
        ex.foundVersion shouldBe 99
        ex.supportedVersion shouldBe CheckpointManifest.CURRENT_SCHEMA_VERSION
        ex.message!! shouldContain "schemaVersion=99"
    }

    // ── MigrationDdlPayload ───────────────────────────────────────────

    test("MigrationDdlPayload properties") {
        val ddlResult = DdlResult(listOf(DdlStatement("CREATE TABLE t;")))
        val payload = MigrationDdlPayload(result = ddlResult, deterministicSql = "CREATE TABLE t;")
        payload.result shouldBe ddlResult
        payload.deterministicSql shouldBe "CREATE TABLE t;"
    }

    test("MigrationDdlPayload equality") {
        val r = DdlResult(listOf(DdlStatement("SELECT 1;")))
        val a = MigrationDdlPayload(r, "SELECT 1;")
        val b = MigrationDdlPayload(r, "SELECT 1;")
        a shouldBe b
    }

    test("MigrationDdlPayload copy") {
        val r = DdlResult(listOf(DdlStatement("SELECT 1;")))
        val p = MigrationDdlPayload(r, "SELECT 1;")
        val c = p.copy(deterministicSql = "SELECT 2;")
        c.deterministicSql shouldBe "SELECT 2;"
    }

    // ── MigrationRollback sealed interface ────────────────────────────

    test("MigrationRollback.NotRequested is singleton") {
        val a: MigrationRollback = MigrationRollback.NotRequested
        val b: MigrationRollback = MigrationRollback.NotRequested
        (a === b) shouldBe true
    }

    test("MigrationRollback.Requested carries payload") {
        val ddlResult = DdlResult(listOf(DdlStatement("DROP TABLE t;")))
        val payload = MigrationDdlPayload(ddlResult, "DROP TABLE t;")
        val r = MigrationRollback.Requested(down = payload)
        r.down shouldBe payload
    }

    test("MigrationRollback.Requested equality") {
        val p = MigrationDdlPayload(DdlResult(emptyList()), "")
        val a = MigrationRollback.Requested(p)
        val b = MigrationRollback.Requested(p)
        a shouldBe b
    }

    // ── MigrationBundle ───────────────────────────────────────────────

    test("MigrationBundle properties") {
        val identity = MigrationIdentity(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0",
            MigrationVersionSource.CLI, "init",
        )
        val schema = dev.dmigrate.core.model.SchemaDefinition(name = "test", version = "1.0")
        val options = DdlGenerationOptions()
        val upResult = DdlResult(listOf(DdlStatement("CREATE TABLE t;")))
        val up = MigrationDdlPayload(upResult, "CREATE TABLE t;")
        val rollback = MigrationRollback.NotRequested

        val bundle = MigrationBundle(identity, schema, options, up, rollback)
        bundle.identity shouldBe identity
        bundle.schema shouldBe schema
        bundle.options shouldBe options
        bundle.up shouldBe up
        bundle.rollback shouldBe rollback
    }

    test("MigrationBundle equality") {
        val identity = MigrationIdentity(
            MigrationTool.FLYWAY, DatabaseDialect.POSTGRESQL, "1.0",
            MigrationVersionSource.CLI, "init",
        )
        val schema = dev.dmigrate.core.model.SchemaDefinition(name = "test", version = "1.0")
        val options = DdlGenerationOptions()
        val up = MigrationDdlPayload(DdlResult(emptyList()), "")
        val rollback = MigrationRollback.NotRequested

        val a = MigrationBundle(identity, schema, options, up, rollback)
        val b = MigrationBundle(identity, schema, options, up, rollback)
        a shouldBe b
    }

    // ── TableImportSummary ────────────────────────────────────────────

    test("TableImportSummary properties and defaults") {
        val summary = TableImportSummary(
            table = "users", rowsInserted = 100, rowsUpdated = 5, rowsSkipped = 2,
            rowsUnknown = 0, rowsFailed = 0, chunkFailures = emptyList(),
            sequenceAdjustments = emptyList(), targetColumns = emptyList(),
            triggerMode = TriggerMode.FIRE, durationMs = 500,
        )
        summary.error shouldBe null
        summary.failedFinish shouldBe null
        summary.table shouldBe "users"
    }

    test("TableImportSummary equality") {
        val a = TableImportSummary(
            "t", 1, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(),
            TriggerMode.FIRE, durationMs = 1,
        )
        val b = TableImportSummary(
            "t", 1, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(),
            TriggerMode.FIRE, durationMs = 1,
        )
        a shouldBe b
    }

    // ── MigrationArtifact ─────────────────────────────────────────────

    test("MigrationArtifact properties") {
        val path = dev.dmigrate.migration.ArtifactRelativePath.of("V1__init.sql")
        val artifact = dev.dmigrate.migration.MigrationArtifact(
            relativePath = path, kind = "up", content = "CREATE TABLE t;",
        )
        artifact.relativePath shouldBe path
        artifact.kind shouldBe "up"
        artifact.content shouldBe "CREATE TABLE t;"
    }

    test("MigrationArtifact equality") {
        val path = dev.dmigrate.migration.ArtifactRelativePath.of("V1__init.sql")
        val a = dev.dmigrate.migration.MigrationArtifact(path, "up", "sql")
        val b = dev.dmigrate.migration.MigrationArtifact(path, "up", "sql")
        a shouldBe b
    }

    test("MigrationArtifact copy") {
        val path = dev.dmigrate.migration.ArtifactRelativePath.of("V1__init.sql")
        val a = dev.dmigrate.migration.MigrationArtifact(path, "up", "sql")
        val c = a.copy(kind = "down")
        c.kind shouldBe "down"
    }

    // ── ExportOutput.resolve edge: existing directory ─────────────────

    test("ExportOutput.resolve returns FilePerTable for existing directory with split-files") {
        val tmpDir = java.nio.file.Files.createTempDirectory("export-test")
        try {
            val result = ExportOutput.resolve(outputPath = tmpDir, splitFiles = true, tableCount = 2)
            result shouldBe ExportOutput.FilePerTable(tmpDir)
        } finally {
            java.nio.file.Files.deleteIfExists(tmpDir)
        }
    }

    test("ExportOutput.resolve throws for existing file with split-files") {
        val tmpFile = java.nio.file.Files.createTempFile("export-test", ".json")
        try {
            shouldThrow<IllegalArgumentException> {
                ExportOutput.resolve(outputPath = tmpFile, splitFiles = true, tableCount = 1)
            }.message!! shouldContain "must be a directory"
        } finally {
            java.nio.file.Files.deleteIfExists(tmpFile)
        }
    }

    // ── ExportResult copy ─────────────────────────────────────────────

    test("ExportResult copy") {
        val r = ExportResult(emptyList(), 0, 0, 0, 0)
        val c = r.copy(operationId = "op-2")
        c.operationId shouldBe "op-2"
    }

    // ── ImportResult copy ─────────────────────────────────────────────

    test("ImportResult copy") {
        val r = ImportResult(emptyList(), 0, 0, 0, 0, 0, 0)
        val c = r.copy(operationId = "op-3")
        c.operationId shouldBe "op-3"
    }

    // ── PipelineConfig with CheckpointConfig ──────────────────────────

    test("PipelineConfig with checkpoint enabled") {
        val cc = CheckpointConfig(enabled = true, directory = Path.of("/tmp/ckpt"))
        val pc = PipelineConfig(chunkSize = 5000, checkpoint = cc)
        pc.checkpoint.enabled shouldBe true
        pc.checkpoint.directory shouldBe Path.of("/tmp/ckpt")
    }

    // ── ProgressEvent.RunStarted copy ─────────────────────────────────

    test("ProgressEvent.RunStarted copy") {
        val e = ProgressEvent.RunStarted(ProgressOperation.EXPORT, 1)
        val c = e.copy(resuming = true)
        c.resuming shouldBe true
    }
})
