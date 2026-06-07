package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.NoOpProgressReporter
import dev.dmigrate.streaming.checkpoint.BundleCheckpointSpecifics
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointOperationType
import dev.dmigrate.streaming.checkpoint.CheckpointReference
import dev.dmigrate.streaming.checkpoint.CheckpointSliceStatus
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointTableSlice
import dev.dmigrate.streaming.checkpoint.SingleFileCheckpointSpecifics
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.time.Instant

/**
 * S8c (AP9 §7.5 / AP11 §6.4): Vertragstests fuer
 * `ImportCheckpointManager.validateManifest` mit den neuen
 * `CheckpointOperationSpecifics`-Branches, plus Pre-AP8-Branch und
 * `writeInitialManifest`-Persistenz.
 */
class ImportCheckpointManagerOperationSpecificsTest : FunSpec({

    val fixedNow = Instant.parse("2026-06-09T10:00:00Z")

    class Capture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
    }

    class RecordingStore(initial: CheckpointManifest? = null) : CheckpointStore {
        private var current: CheckpointManifest? = initial
        val saved = mutableListOf<CheckpointManifest>()
        override fun load(operationId: String): CheckpointManifest? = current
        override fun save(manifest: CheckpointManifest) {
            current = manifest
            saved += manifest
        }
        override fun list(): List<CheckpointReference> = emptyList()
        override fun complete(operationId: String) { current = null }
    }

    fun importManager(stderr: Capture) = ImportCheckpointManager(
        checkpointStoreFactory = null,
        checkpointConfigResolver = { null },
        clock = { fixedNow },
        progressReporter = NoOpProgressReporter,
        stderr = stderr.sink,
    )

    fun requestWithResume(operationId: String) = DataImportRequest(
        target = "sqlite:///tmp/test.db",
        source = "/tmp/in.parquet",
        format = "parquet",
        schema = null,
        table = "public.users",
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
        resume = operationId,
        checkpointDir = null,
    )

    fun freshImportRequest() = DataImportRequest(
        target = "sqlite:///tmp/test.db",
        source = "/tmp/in.parquet",
        format = "parquet",
        schema = null,
        table = "public.users",
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

    val sampleBundleFingerprint = BundleResumeFingerprint(
        manifestSha256 = "a".repeat(64),
        formatVersion = "1",
        producerVersion = "0.9.8",
        tableOrder = listOf("public.users"),
    )

    fun bundleManifest(operationId: String, fingerprint: BundleResumeFingerprint) = CheckpointManifest(
        operationId = operationId,
        operationType = CheckpointOperationType.IMPORT,
        createdAt = fixedNow,
        updatedAt = fixedNow,
        format = "parquet",
        chunkSize = 1000,
        tableSlices = listOf(
            CheckpointTableSlice(table = "public.users", status = CheckpointSliceStatus.PENDING),
        ),
        optionsFingerprint = "fp-bundle",
        operationSpecific = BundleCheckpointSpecifics(fingerprint),
    )

    fun singleFileManifest(operationId: String, contentSha256: String, table: String) = CheckpointManifest(
        operationId = operationId,
        operationType = CheckpointOperationType.IMPORT,
        createdAt = fixedNow,
        updatedAt = fixedNow,
        format = "parquet",
        chunkSize = 1000,
        tableSlices = listOf(
            CheckpointTableSlice(table = table, status = CheckpointSliceStatus.PENDING),
        ),
        optionsFingerprint = "fp-single",
        operationSpecific = SingleFileCheckpointSpecifics(contentSha256, table),
    )

    fun bundleInputContext(
        fingerprint: BundleResumeFingerprint = sampleBundleFingerprint,
        shaByTable: Map<String, String?> = mapOf("public.users" to "x".repeat(64)),
        optionsFp: String = "fp-bundle",
    ) = InputContext(
        effectiveTables = shaByTable.keys.toList(),
        inputFilesByTable = emptyMap(),
        fingerprint = optionsFp,
        bundleExpectedSha256ByTable = shaByTable,
        bundleResumeFingerprint = fingerprint,
    )

    fun singleFileInputContext(
        contentSha256: String? = "b".repeat(64),
        table: String = "public.users",
        optionsFp: String = "fp-single",
    ) = InputContext(
        effectiveTables = listOf(table),
        inputFilesByTable = emptyMap(),
        fingerprint = optionsFp,
        singleFileContentSha256 = contentSha256,
    )

    fun nonParquetInputContext() = InputContext(
        effectiveTables = listOf("public.users"),
        inputFilesByTable = emptyMap(),
        fingerprint = "fp-json",
    )

    test("bundle resume happy path: matching fingerprint + per-table SHA present → OK") {
        val stderr = Capture()
        val store = RecordingStore(bundleManifest("op-bundle-ok", sampleBundleFingerprint))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-bundle-ok").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-resolved-")),
            inputCtx = bundleInputContext(),
        )
        check(result is ImportResumeResult.Ok) { "expected Ok, got $result; stderr=${stderr.lines}" }
    }

    test("bundle resume fingerprint mismatch → Exit 3") {
        val stderr = Capture()
        val differentFp = sampleBundleFingerprint.copy(manifestSha256 = "c".repeat(64))
        val store = RecordingStore(bundleManifest("op-bundle-fp", differentFp))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-bundle-fp").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = bundleInputContext(),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "fingerprint mismatch"
    }

    test("bundle resume with non-bundle current run → Exit 3") {
        val stderr = Capture()
        val store = RecordingStore(bundleManifest("op-bundle-wrongctx", sampleBundleFingerprint))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-bundle-wrongctx").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = nonParquetInputContext().copy(fingerprint = "fp-bundle"),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "current run is not a parquet-bundle"
    }

    test("bundle resume with missing per-table SHA → Exit 3 (BUNDLE_RESUME_REQUIRES_FILE_HASHES)") {
        val stderr = Capture()
        val store = RecordingStore(bundleManifest("op-bundle-nosha", sampleBundleFingerprint))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-bundle-nosha").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = bundleInputContext(shaByTable = mapOf("public.users" to null)),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "requires per-table sha256"
    }

    test("single-file resume happy path: matching hash + table → OK") {
        val stderr = Capture()
        val store = RecordingStore(singleFileManifest("op-sf-ok", "b".repeat(64), "public.users"))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-sf-ok").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = singleFileInputContext(),
        )
        check(result is ImportResumeResult.Ok) { "expected Ok, got $result; stderr=${stderr.lines}" }
    }

    test("single-file resume content sha mismatch → Exit 3") {
        val stderr = Capture()
        val store = RecordingStore(singleFileManifest("op-sf-shamismatch", "b".repeat(64), "public.users"))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-sf-shamismatch").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = singleFileInputContext(contentSha256 = "c".repeat(64)),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "content sha256 mismatch"
    }

    test("single-file resume table mismatch → Exit 3") {
        val stderr = Capture()
        // Konstruktion: tableSlices und effectiveTables sind beide "public.events"
        // (sonst greift der frueher laufende tableSlices-Mismatch); nur die
        // im operationSpecific persistierte Tabelle (specifics.table) zeigt
        // auf eine andere Tabelle — Simulation eines manipulierten/migrierten
        // Manifests, das nur durch den S8c-SingleFile-Check abgefangen wird.
        val manifest = CheckpointManifest(
            operationId = "op-sf-tablemismatch",
            operationType = CheckpointOperationType.IMPORT,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            format = "parquet",
            chunkSize = 1000,
            tableSlices = listOf(
                CheckpointTableSlice(table = "public.events", status = CheckpointSliceStatus.PENDING),
            ),
            optionsFingerprint = "fp-single",
            operationSpecific = SingleFileCheckpointSpecifics(
                contentSha256 = "b".repeat(64),
                table = "public.users", // bewusst abweichend
            ),
        )
        val store = RecordingStore(manifest)
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-sf-tablemismatch").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = singleFileInputContext(table = "public.events"),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "table mismatch"
    }

    test("single-file resume with current run lacking content sha → Exit 3") {
        val stderr = Capture()
        val store = RecordingStore(singleFileManifest("op-sf-nohash", "b".repeat(64), "public.users"))
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-sf-nohash").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = singleFileInputContext(contentSha256 = null),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "did not compute a content sha256"
    }

    test("Pre-AP8 manifest + Parquet-bundle current run → Exit 3 (BUNDLE_CHECKPOINT_MISSING_BUNDLE_FINGERPRINT)") {
        val stderr = Capture()
        // operationSpecific = null simuliert ein Pre-AP8-Manifest.
        val preAp8 = bundleManifest("op-pre-ap8-bundle", sampleBundleFingerprint).copy(operationSpecific = null)
        val store = RecordingStore(preAp8)
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-pre-ap8-bundle").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = bundleInputContext(),
        )
        result shouldBe ImportResumeResult.Exit(3)
        stderr.lines.joinToString("\n") shouldContain "Pre-0.9.8 checkpoint without bundle/single-file fingerprint"
    }

    test("Pre-AP8 manifest + non-Parquet (JSON) current run → OK") {
        val stderr = Capture()
        val preAp8 = CheckpointManifest(
            operationId = "op-pre-ap8-json",
            operationType = CheckpointOperationType.IMPORT,
            createdAt = fixedNow,
            updatedAt = fixedNow,
            format = "json",
            chunkSize = 1000,
            tableSlices = listOf(
                CheckpointTableSlice(table = "public.users", status = CheckpointSliceStatus.PENDING),
            ),
            optionsFingerprint = "fp-json",
        )
        val store = RecordingStore(preAp8)
        val result = importManager(stderr).resolveResumeContext(
            request = requestWithResume("op-pre-ap8-json").copy(checkpointDir = Files.createTempDirectory("ckpt-")),
            checkpoint = ImportCheckpointContext(store, Files.createTempDirectory("ckpt-")),
            inputCtx = nonParquetInputContext(),
        )
        check(result is ImportResumeResult.Ok) { "expected Ok, got $result; stderr=${stderr.lines}" }
    }

    test("writeInitialManifest persists BundleCheckpointSpecifics for bundle runs") {
        val stderr = Capture()
        val store = RecordingStore()
        val resumeCtx = ImportResumeContext(
            operationId = "op-init-bundle",
            resuming = false,
            skippedTables = emptySet(),
            resumeStateByTable = emptyMap(),
            initialSlices = mapOf(
                "public.users" to CheckpointTableSlice(table = "public.users", status = CheckpointSliceStatus.PENDING),
            ),
        )
        val exit = importManager(stderr).writeInitialManifest(
            request = freshImportRequest(),
            format = DataExportFormat.PARQUET,
            resumeCtx = resumeCtx,
            store = store,
            inputCtx = bundleInputContext(),
        )
        exit shouldBe null
        store.saved.size shouldBe 1
        val saved = store.saved.single().operationSpecific
        check(saved is BundleCheckpointSpecifics) { "expected BundleCheckpointSpecifics, got $saved" }
        saved.fingerprint shouldBe sampleBundleFingerprint
    }

    test("writeInitialManifest persists SingleFileCheckpointSpecifics for single-file runs") {
        val stderr = Capture()
        val store = RecordingStore()
        val resumeCtx = ImportResumeContext(
            operationId = "op-init-sf",
            resuming = false,
            skippedTables = emptySet(),
            resumeStateByTable = emptyMap(),
            initialSlices = mapOf(
                "public.users" to CheckpointTableSlice(table = "public.users", status = CheckpointSliceStatus.PENDING),
            ),
        )
        val exit = importManager(stderr).writeInitialManifest(
            request = freshImportRequest(),
            format = DataExportFormat.PARQUET,
            resumeCtx = resumeCtx,
            store = store,
            inputCtx = singleFileInputContext(),
        )
        exit shouldBe null
        store.saved.size shouldBe 1
        val saved = store.saved.single().operationSpecific
        check(saved is SingleFileCheckpointSpecifics) { "expected SingleFileCheckpointSpecifics, got $saved" }
        saved.contentSha256 shouldBe "b".repeat(64)
        saved.table shouldBe "public.users"
    }

    test("writeInitialManifest leaves operationSpecific=null for non-Parquet runs") {
        val stderr = Capture()
        val store = RecordingStore()
        val resumeCtx = ImportResumeContext(
            operationId = "op-init-json",
            resuming = false,
            skippedTables = emptySet(),
            resumeStateByTable = emptyMap(),
            initialSlices = mapOf(
                "public.users" to CheckpointTableSlice(table = "public.users", status = CheckpointSliceStatus.PENDING),
            ),
        )
        val exit = importManager(stderr).writeInitialManifest(
            request = freshImportRequest(),
            format = DataExportFormat.JSON,
            resumeCtx = resumeCtx,
            store = store,
            inputCtx = nonParquetInputContext(),
        )
        exit shouldBe null
        store.saved.size shouldBe 1
        store.saved.single().operationSpecific shouldBe null
    }
})
