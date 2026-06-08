package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.streaming.FailedFinishInfo
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointReference
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

class DataImportHelpersTest : FunSpec({

    fun request(
        target: String? = "sqlite:///tmp/test.db",
        source: String = "/tmp/users.json",
        format: String? = null,
        table: String? = "users",
        tables: List<String>? = null,
        schema: Path? = null,
        triggerMode: String = "fire",
        disableFkChecks: Boolean = false,
        encoding: String? = null,
    ) = DataImportRequest(
        target = target,
        source = source,
        format = format,
        schema = schema,
        table = table,
        tables = tables,
        onError = "abort",
        onConflict = null,
        triggerMode = triggerMode,
        truncate = false,
        disableFkChecks = disableFkChecks,
        reseedSequences = true,
        encoding = encoding,
        csvNoHeader = false,
        csvNullString = "",
        chunkSize = 10_000,
        cliConfigPath = null,
        quiet = false,
        noProgress = false,
    )

    test("inferFormatFromExtension detects supported extensions") {
        DataImportHelpers.inferFormatFromExtension(Path.of("/tmp/users.json")) shouldBe "json"
        DataImportHelpers.inferFormatFromExtension(Path.of("/tmp/users.yaml")) shouldBe "yaml"
        DataImportHelpers.inferFormatFromExtension(Path.of("/tmp/users.csv")) shouldBe "csv"
        DataImportHelpers.inferFormatFromExtension(Path.of("/tmp/users.parquet")) shouldBe "parquet"
    }

    test("resolveFormat reports missing stdin format") {
        val stderr = mutableListOf<String>()

        val format = DataImportHelpers.resolveFormat(
            request(source = "-", format = null),
            isStdin = true,
            sourcePath = null,
            stderr = { stderr += it },
        )

        format shouldBe null
        stderr.single() shouldContain "--format is required"
    }

    test("resolveFormat error message lists parquet as a supported format") {
        val stderr = mutableListOf<String>()

        val format = DataImportHelpers.resolveFormat(
            request(source = "/tmp/no-ext"),
            isStdin = false,
            sourcePath = Path.of("/tmp/no-ext"),
            stderr = { stderr += it },
        )

        format shouldBe null
        stderr.single() shouldContain "json, yaml, csv, or parquet"
    }

    test("resolveFormat infers parquet from .parquet extension") {
        val stderr = mutableListOf<String>()

        val format = DataImportHelpers.resolveFormat(
            request(source = "/tmp/users.parquet"),
            isStdin = false,
            sourcePath = Path.of("/tmp/users.parquet"),
            stderr = { stderr += it },
        )

        format shouldBe dev.dmigrate.format.data.DataExportFormat.PARQUET
        stderr.shouldBeEmpty()
    }

    test("resolveFormat infers parquet from directory with bundle-shaped manifest.yaml") {
        val stderr = mutableListOf<String>()
        val bundleDir = Files.createTempDirectory("parquet-bundle-")
        try {
            // Minimal bundle manifest with the two markers required by the
            // false-positive-resistant sniffer (formatVersion + tables).
            Files.writeString(
                bundleDir.resolve("manifest.yaml"),
                """
                formatVersion: '1.0'
                producer: d-migrate
                tables:
                  - table: users
                    file: users.parquet
                """.trimIndent()
            )

            val format = DataImportHelpers.resolveFormat(
                request(source = bundleDir.toString()),
                isStdin = false,
                sourcePath = bundleDir,
                stderr = { stderr += it },
            )

            format shouldBe dev.dmigrate.format.data.DataExportFormat.PARQUET
            stderr.shouldBeEmpty()
        } finally {
            bundleDir.toFile().deleteRecursively()
        }
    }

    test("resolveFormat does not infer parquet from directory with non-parquet manifest.yaml (Helm-style)") {
        val stderr = mutableListOf<String>()
        val helmDir = Files.createTempDirectory("helm-like-")
        try {
            // Stray manifest.yaml without the bundle marker fields.
            Files.writeString(
                helmDir.resolve("manifest.yaml"),
                "apiVersion: v2\nname: my-chart\nversion: 0.1.0\n"
            )

            val format = DataImportHelpers.resolveFormat(
                request(source = helmDir.toString()),
                isStdin = false,
                sourcePath = helmDir,
                stderr = { stderr += it },
            )

            format shouldBe null
            stderr.single() shouldContain "Cannot detect format"
        } finally {
            helmDir.toFile().deleteRecursively()
        }
    }

    test("resolveFormat does not infer parquet from directory with empty manifest.yaml") {
        val stderr = mutableListOf<String>()
        val dir = Files.createTempDirectory("empty-manifest-")
        try {
            Files.writeString(dir.resolve("manifest.yaml"), "")

            val format = DataImportHelpers.resolveFormat(
                request(source = dir.toString()),
                isStdin = false,
                sourcePath = dir,
                stderr = { stderr += it },
            )

            format shouldBe null
            stderr.single() shouldContain "Cannot detect format"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("resolveFormat does not infer parquet from directory without manifest.yaml") {
        val stderr = mutableListOf<String>()
        val plainDir = Files.createTempDirectory("plain-dir-")
        try {
            val format = DataImportHelpers.resolveFormat(
                request(source = plainDir.toString()),
                isStdin = false,
                sourcePath = plainDir,
                stderr = { stderr += it },
            )

            format shouldBe null
            stderr.single() shouldContain "Cannot detect format"
        } finally {
            plainDir.toFile().deleteRecursively()
        }
    }

    test("validateFormatPathRequirements rejects parquet with stdin source") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateFormatPathRequirements(
            format = dev.dmigrate.format.data.DataExportFormat.PARQUET,
            isStdin = true,
            stderr = stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "stdin"
        stderr.single() shouldContain "parquet"
    }

    test("validateFormatPathRequirements passes for parquet with a path source") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateFormatPathRequirements(
            format = dev.dmigrate.format.data.DataExportFormat.PARQUET,
            isStdin = false,
            stderr = stderr::add,
        )

        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateFormatPathRequirements passes for non-parquet stdin source") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateFormatPathRequirements(
            format = dev.dmigrate.format.data.DataExportFormat.JSON,
            isStdin = true,
            stderr = stderr::add,
        )

        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects conflicting table selectors") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request(table = "users", tables = listOf("orders")),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "mutually exclusive"
    }

    test("validateCliFlags rejects --table combined with --table-order") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request(table = "users").copy(tableOrder = listOf("users", "orders")),
            stderr::add,
        )
        exit shouldBe 2
        stderr.single() shouldContain "--table and --table-order are mutually exclusive"
    }

    test("validateCliFlags rejects --table-order on stdin import") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request(source = "-", table = null).copy(tableOrder = listOf("users", "orders")),
            stderr::add,
        )
        exit shouldBe 2
        stderr.single() shouldContain "--table-order is not supported for stdin"
    }

    test("validateCliFlags accepts --table-order with valid identifiers on directory source") {
        val stderr = mutableListOf<String>()
        val exit = DataImportHelpers.validateCliFlags(
            request(source = "/tmp/bundle", table = null).copy(tableOrder = listOf("public.users", "orders")),
            stderr::add,
        )
        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects --no-checkpoint combined with --resume") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(resume = "run-123", noCheckpoint = true),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--no-checkpoint and --resume are mutually exclusive"
    }

    test("validateCliFlags accepts --no-checkpoint alone") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(noCheckpoint = true),
            stderr::add,
        )

        exit shouldBe null
        stderr.shouldBeEmpty()
    }

    test("validateCliFlags rejects --no-checkpoint combined with --checkpoint-dir") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request().copy(noCheckpoint = true, checkpointDir = Path.of("/tmp/dummy")),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--no-checkpoint and --checkpoint-dir are mutually exclusive"
    }

    test("resolveImportInput allows missing --table for Parquet single-file (sentinel placeholder)") {
        val sourceFile = Path.of("/tmp/users.parquet")

        val input = DataImportHelpers.resolveImportInput(
            request(table = null, format = "parquet", source = sourceFile.toString()),
            isStdin = false,
            sourcePath = sourceFile,
            stdinProvider = { ByteArrayInputStream("".toByteArray()) },
            format = dev.dmigrate.format.data.DataExportFormat.PARQUET,
        )

        input shouldBe ImportInput.SingleFile(UNRESOLVED_PARQUET_TABLE_SENTINEL, sourceFile)
    }

    test("resolveImportInput keeps --table for non-Parquet single-file") {
        val sourceFile = Path.of("/tmp/users.json")
        val ex = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            DataImportHelpers.resolveImportInput(
                request(table = null, format = "json", source = sourceFile.toString()),
                isStdin = false,
                sourcePath = sourceFile,
                stdinProvider = { ByteArrayInputStream("".toByteArray()) },
                format = dev.dmigrate.format.data.DataExportFormat.JSON,
            )
        }
        ex.message!! shouldContain "--table is required when importing from a single file"
        ex.message!! shouldContain "not required for --format parquet"
    }

    test("validateCliFlags rejects resume on stdin import") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateCliFlags(
            request(source = "-").copy(resume = "run-123"),
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--resume is not supported for stdin import"
    }

    test("resolveSchemaPreflight maps ImportPreflightException to exit 3") {
        val stderr = mutableListOf<String>()

        val result = DataImportHelpers.resolveSchemaPreflight(
            request(schema = Path.of("/tmp/schema.yaml")),
            ImportInput.SingleFile("users", Path.of("/tmp/users.json")),
            format = dev.dmigrate.format.data.DataExportFormat.JSON,
            schemaPreflight = { _, _, _, _ -> throw ImportPreflightException("schema mismatch") },
            stderr = stderr::add,
        )

        result shouldBe ImportStep.Exit(3)
        stderr.single() shouldContain "schema mismatch"
    }

    test("resolveImportInput returns stdin input for dash source") {
        val input = DataImportHelpers.resolveImportInput(
            request(source = "-", format = "json", table = "users"),
            isStdin = true,
            sourcePath = null,
            stdinProvider = { ByteArrayInputStream("[]".toByteArray()) },
        )

        (input as ImportInput.Stdin).table shouldBe "users"
    }

    test("resolveCharset returns parsed charset") {
        val result = DataImportHelpers.resolveCharset("UTF-8") { error("unexpected stderr") }

        result shouldBe ImportStep.Ok(Charset.forName("UTF-8"))
    }

    test("resolveCharset maps unknown encoding to exit 2") {
        val stderr = mutableListOf<String>()

        val result = DataImportHelpers.resolveCharset("definitely-not-a-charset", stderr::add)

        result shouldBe ImportStep.Exit(2)
        stderr.single() shouldContain "Unknown encoding"
    }

    test("resolveTargetContext returns resolved url and parsed connection config") {
        val config = ConnectionConfig(
            dialect = DatabaseDialect.SQLITE,
            host = null,
            port = null,
            database = "/tmp/test.db",
            user = null,
            password = null,
        )

        val result = DataImportHelpers.resolveTargetContext(
            request(target = "sqlite:///tmp/test.db"),
            targetResolver = { target, _ -> target ?: error("expected target") },
            urlParser = { config },
            stderr = { error("unexpected stderr") },
        )

        result shouldBe ImportStep.Ok(ImportTargetContext("sqlite:///tmp/test.db", config))
    }

    test("resolveTargetContext maps CliUsageException to exit 2") {
        val stderr = mutableListOf<String>()

        val result = DataImportHelpers.resolveTargetContext(
            request(),
            targetResolver = { _, _ -> throw CliUsageException("named connection missing") },
            urlParser = { error("should not parse") },
            stderr = stderr::add,
        )

        result shouldBe ImportStep.Exit(2)
        stderr.single() shouldContain "named connection missing"
    }

    test("validateDialectCapabilities rejects unsupported foreign key disable") {
        val stderr = mutableListOf<String>()

        val exit = DataImportHelpers.validateDialectCapabilities(
            request(disableFkChecks = true),
            DatabaseDialect.POSTGRESQL,
            stderr::add,
        )

        exit shouldBe 2
        stderr.single() shouldContain "--disable-fk-checks"
    }

    test("assessCompletion returns exit for per-table error") {
        val assessment = ImportCompletionSupport.assessCompletion(
            ImportResult(
                tables = listOf(
                    TableImportSummary(
                        table = "users",
                        rowsInserted = 0,
                        rowsUpdated = 0,
                        rowsSkipped = 0,
                        rowsUnknown = 0,
                        rowsFailed = 1,
                        chunkFailures = emptyList(),
                        sequenceAdjustments = emptyList(),
                        targetColumns = emptyList(),
                        triggerMode = TriggerMode.FIRE,
                        error = "duplicate key",
                        durationMs = 1,
                    )
                ),
                totalRowsInserted = 0,
                totalRowsUpdated = 0,
                totalRowsSkipped = 0,
                totalRowsUnknown = 0,
                totalRowsFailed = 1,
                durationMs = 1,
            )
        )

        assessment shouldBe ImportCompletionAssessment.Exit(
            code = 5,
            message = "Error: Failed to import table 'users': duplicate key",
        )
    }

    test("formatProgressSummary includes updates failures and reseeded sequences") {
        val summary = ImportCompletionSupport.formatProgressSummary(
            ImportResult(
                tables = listOf(
                    TableImportSummary(
                        table = "users",
                        rowsInserted = 100,
                        rowsUpdated = 20,
                        rowsSkipped = 0,
                        rowsUnknown = 0,
                        rowsFailed = 3,
                        chunkFailures = emptyList(),
                        sequenceAdjustments = listOf(
                            SequenceAdjustment("users", "id", "users_id_seq", 121)
                        ),
                        targetColumns = emptyList(),
                        triggerMode = TriggerMode.FIRE,
                        durationMs = 500,
                    )
                ),
                totalRowsInserted = 100,
                totalRowsUpdated = 20,
                totalRowsSkipped = 0,
                totalRowsUnknown = 0,
                totalRowsFailed = 3,
                durationMs = 500,
            )
        )

        summary shouldContain "Imported 1 table(s)"
        summary shouldContain "100 inserted"
        summary shouldContain "20 updated"
        summary shouldContain "3 failed"
        summary shouldContain "reseeded 1 sequence(s)"
    }

    test("finalizeAndReport completes checkpoint and prints summary") {
        val stderr = mutableListOf<String>()
        val (store, completedOperationIds) = recordingCheckpointStore()
        val result = successfulImportResult(operationId = "run-123")

        val exit = ImportCompletionSupport.finalizeAndReport(
            request(),
            result,
            store,
            operationId = "run-123",
            stderr = stderr::add,
        )

        exit shouldBe 0
        completedOperationIds shouldBe listOf("run-123")
        stderr[0] shouldContain "Imported 1 table(s)"
        stderr[1] shouldContain "Run operation id: run-123"
    }

    test("finalizeAndReport warns when checkpoint cleanup fails") {
        val stderr = mutableListOf<String>()
        val (store, _) = recordingCheckpointStore(completeFailure = "disk busy")

        val exit = ImportCompletionSupport.finalizeAndReport(
            request(),
            successfulImportResult(),
            store,
            operationId = "run-123",
            stderr = stderr::add,
        )

        exit shouldBe 0
        stderr[0] shouldContain "Failed to remove completed checkpoint"
        stderr[1] shouldContain "Imported 1 table(s)"
    }

    test("finalizeAndReport returns failure without checkpoint cleanup") {
        val stderr = mutableListOf<String>()
        val (store, completedOperationIds) = recordingCheckpointStore()

        val exit = ImportCompletionSupport.finalizeAndReport(
            request(),
            failingImportResult(),
            store,
            operationId = "run-123",
            stderr = stderr::add,
        )

        exit shouldBe 5
        completedOperationIds shouldBe emptyList<String>()
        stderr.single() shouldContain "Failed to import table 'users'"
    }

    test("assessCompletion returns exit for failed finish") {
        val assessment = ImportCompletionSupport.assessCompletion(
            ImportResult(
                tables = listOf(
                    TableImportSummary(
                        table = "users",
                        rowsInserted = 10,
                        rowsUpdated = 0,
                        rowsSkipped = 0,
                        rowsUnknown = 0,
                        rowsFailed = 0,
                        chunkFailures = emptyList(),
                        sequenceAdjustments = emptyList(),
                        targetColumns = emptyList(),
                        triggerMode = TriggerMode.FIRE,
                        failedFinish = FailedFinishInfo(
                            adjustments = emptyList(),
                            causeMessage = "trigger re-enable failed",
                            causeClass = "SQLException",
                        ),
                        durationMs = 1,
                    )
                ),
                totalRowsInserted = 10,
                totalRowsUpdated = 0,
                totalRowsSkipped = 0,
                totalRowsUnknown = 0,
                totalRowsFailed = 0,
                durationMs = 1,
            )
        )

        assessment shouldBe ImportCompletionAssessment.Exit(
            code = 5,
            message = "Error: Post-import finalization failed for table 'users': trigger re-enable failed. " +
                "Data was committed - manual post-import fix may be needed.",
        )
    }

    test("assessCompletion emits BUNDLE_TABLE_IMPORT_FAILED for parquet-bundle per-table error (S9a-0.d)") {
        val assessment = ImportCompletionSupport.assessCompletion(
            ImportResult(
                tables = listOf(
                    TableImportSummary(
                        table = "orders",
                        rowsInserted = 0,
                        rowsUpdated = 0,
                        rowsSkipped = 0,
                        rowsUnknown = 0,
                        rowsFailed = 1,
                        chunkFailures = emptyList(),
                        sequenceAdjustments = emptyList(),
                        targetColumns = emptyList(),
                        triggerMode = TriggerMode.FIRE,
                        error = "FK violation",
                        durationMs = 1,
                    )
                ),
                totalRowsInserted = 0,
                totalRowsUpdated = 0,
                totalRowsSkipped = 0,
                totalRowsUnknown = 0,
                totalRowsFailed = 1,
                durationMs = 1,
            ),
            isParquetBundle = true,
        )

        assessment shouldBe ImportCompletionAssessment.Exit(
            code = 5,
            message = "Error: BUNDLE_TABLE_IMPORT_FAILED: table='orders' cause='FK violation'",
        )
    }

    test("assessCompletion emits BUNDLE_TABLE_IMPORT_FAILED for parquet-bundle failed finish (S9a-0.d)") {
        val assessment = ImportCompletionSupport.assessCompletion(
            ImportResult(
                tables = listOf(
                    TableImportSummary(
                        table = "users",
                        rowsInserted = 10,
                        rowsUpdated = 0,
                        rowsSkipped = 0,
                        rowsUnknown = 0,
                        rowsFailed = 0,
                        chunkFailures = emptyList(),
                        sequenceAdjustments = emptyList(),
                        targetColumns = emptyList(),
                        triggerMode = TriggerMode.FIRE,
                        failedFinish = FailedFinishInfo(
                            adjustments = emptyList(),
                            causeMessage = "trigger re-enable failed",
                            causeClass = "SQLException",
                        ),
                        durationMs = 1,
                    )
                ),
                totalRowsInserted = 10,
                totalRowsUpdated = 0,
                totalRowsSkipped = 0,
                totalRowsUnknown = 0,
                totalRowsFailed = 0,
                durationMs = 1,
            ),
            isParquetBundle = true,
        )

        assessment shouldBe ImportCompletionAssessment.Exit(
            code = 5,
            message = "Error: BUNDLE_TABLE_IMPORT_FAILED: table='users' cause='trigger re-enable failed' " +
                "(post-import finalization; data was committed - manual fix may be needed)",
        )
    }

})

private fun recordingCheckpointStore(
    completeFailure: String? = null,
) : Pair<CheckpointStore, MutableList<String>> {
    val completedOperationIds = mutableListOf<String>()
    val store = object : CheckpointStore {
        override fun load(operationId: String): CheckpointManifest? = null

        override fun save(manifest: CheckpointManifest) = Unit

        override fun list(): List<CheckpointReference> = emptyList()

        override fun complete(operationId: String) {
            completeFailure?.let { throw CheckpointStoreException(it) }
            completedOperationIds += operationId
        }
    }
    return store to completedOperationIds
}

private fun successfulImportResult(
    operationId: String? = null,
) = ImportResult(
    tables = listOf(
        TableImportSummary(
            table = "users",
            rowsInserted = 10,
            rowsUpdated = 1,
            rowsSkipped = 0,
            rowsUnknown = 0,
            rowsFailed = 0,
            chunkFailures = emptyList(),
            sequenceAdjustments = emptyList(),
            targetColumns = emptyList(),
            triggerMode = TriggerMode.FIRE,
            durationMs = 100,
        )
    ),
    totalRowsInserted = 10,
    totalRowsUpdated = 1,
    totalRowsSkipped = 0,
    totalRowsUnknown = 0,
    totalRowsFailed = 0,
    durationMs = 100,
    operationId = operationId,
)

private fun failingImportResult() = ImportResult(
    tables = listOf(
        TableImportSummary(
            table = "users",
            rowsInserted = 0,
            rowsUpdated = 0,
            rowsSkipped = 0,
            rowsUnknown = 0,
            rowsFailed = 1,
            chunkFailures = emptyList(),
            sequenceAdjustments = emptyList(),
            targetColumns = emptyList(),
            triggerMode = TriggerMode.FIRE,
            error = "duplicate key",
            durationMs = 1,
        )
    ),
    totalRowsInserted = 0,
    totalRowsUpdated = 0,
    totalRowsSkipped = 0,
    totalRowsUnknown = 0,
    totalRowsFailed = 1,
    durationMs = 1,
)
