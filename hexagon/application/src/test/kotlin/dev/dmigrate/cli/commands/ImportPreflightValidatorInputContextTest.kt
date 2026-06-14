package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ResolvedBundleTableBinding
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * S8b (AP9 §7.5 / AP11 §6.4): Verifiziert, dass
 * [ImportPreflightValidator.resolveInputContext] die neuen
 * [InputContext]-Felder `bundleExpectedSha256ByTable` und
 * `singleFileContentSha256` korrekt aus dem aufgeloesten
 * [ImportInput] ableitet.
 */
class ImportPreflightValidatorInputContextTest : FunSpec({

    fun buildValidator() = ImportPreflightValidator(
        writerLookup = { throw NotImplementedError("not used in resolveInputContext") },
        schemaTargetValidator = { _, _, _ -> },
        stderr = { },
    )

    fun importRequest(format: String) = DataImportRequest(
        target = "sqlite:///tmp/test.db",
        source = "/tmp/in",
        format = format,
        schema = null,
        table = null,
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

    val sqliteConfig = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = ":memory:",
        user = null,
        password = null,
    )
    val resolvedUrl = "jdbc:sqlite::memory:"

    fun bundleSchema(table: String = "public.users") = ChunkSchema(
        table = table,
        origin = SchemaOrigin.JDBC_METADATA,
        columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
    )

    fun bundleFingerprint() = BundleResumeFingerprint(
        manifestSha256 = "a".repeat(64),
        formatVersion = "1",
        producerVersion = "0.9.8",
        tableOrder = listOf("public.users", "public.orders"),
    )

    test("ResolvedBundle: bundleExpectedSha256ByTable wird befuellt, singleFileContentSha256 bleibt null") {
        val validator = buildValidator()
        val input = ImportInput.ResolvedBundle(
            bundleRoot = Path.of("/tmp/bundle"),
            tables = listOf(
                ResolvedBundleTableBinding(
                    table = "public.users",
                    path = Path.of("/tmp/bundle/users.parquet"),
                    schema = bundleSchema(),
                    expectedSha256 = "a".repeat(64),
                ),
                ResolvedBundleTableBinding(
                    table = "public.orders",
                    path = Path.of("/tmp/bundle/orders.parquet"),
                    schema = bundleSchema("public.orders"),
                    expectedSha256 = null,
                ),
            ),
            resumeFingerprint = bundleFingerprint(),
        )
        val result = validator.resolveInputContext(
            request = importRequest("parquet"),
            connectionConfig = sqliteConfig,
            resolvedUrl = resolvedUrl,
            format = DataExportFormat.PARQUET,
            preparedImport = SchemaPreflightResult(input),
        )
        val ok = result.shouldBeInstance<InputContextResult.Ok>()
        ok.value.bundleExpectedSha256ByTable.shouldNotBeNull()
        ok.value.bundleExpectedSha256ByTable!!["public.users"] shouldBe "a".repeat(64)
        ok.value.bundleExpectedSha256ByTable!!["public.orders"] shouldBe null
        ok.value.singleFileContentSha256.shouldBeNull()
    }

    test("ResolvedSingleFile mit contentSha256: singleFileContentSha256 wird befuellt, Bundle-Map null") {
        val validator = buildValidator()
        val input = ImportInput.ResolvedSingleFile(
            table = "public.events",
            path = Path.of("/tmp/events.parquet"),
            schema = bundleSchema("public.events"),
            contentSha256 = "b".repeat(64),
        )
        val result = validator.resolveInputContext(
            request = importRequest("parquet"),
            connectionConfig = sqliteConfig,
            resolvedUrl = resolvedUrl,
            format = DataExportFormat.PARQUET,
            preparedImport = SchemaPreflightResult(input),
        )
        val ok = result.shouldBeInstance<InputContextResult.Ok>()
        ok.value.singleFileContentSha256 shouldBe "b".repeat(64)
        ok.value.bundleExpectedSha256ByTable.shouldBeNull()
    }

    test("ResolvedSingleFile ohne contentSha256 (Fresh-Run / --no-checkpoint): singleFileContentSha256 bleibt null") {
        val validator = buildValidator()
        val input = ImportInput.ResolvedSingleFile(
            table = "public.events",
            path = Path.of("/tmp/events.parquet"),
            schema = bundleSchema("public.events"),
            contentSha256 = null,
        )
        val result = validator.resolveInputContext(
            request = importRequest("parquet"),
            connectionConfig = sqliteConfig,
            resolvedUrl = resolvedUrl,
            format = DataExportFormat.PARQUET,
            preparedImport = SchemaPreflightResult(input),
        )
        val ok = result.shouldBeInstance<InputContextResult.Ok>()
        ok.value.singleFileContentSha256.shouldBeNull()
        ok.value.bundleExpectedSha256ByTable.shouldBeNull()
    }

    test("Nicht-Parquet-Quelle (SingleFile/Stdin): beide neuen Felder null") {
        val validator = buildValidator()
        val input = ImportInput.SingleFile(
            table = "users",
            path = Path.of("/tmp/users.json"),
        )
        val result = validator.resolveInputContext(
            request = importRequest("json"),
            connectionConfig = sqliteConfig,
            resolvedUrl = resolvedUrl,
            format = DataExportFormat.JSON,
            preparedImport = SchemaPreflightResult(input),
        )
        val ok = result.shouldBeInstance<InputContextResult.Ok>()
        ok.value.bundleExpectedSha256ByTable.shouldBeNull()
        ok.value.singleFileContentSha256.shouldBeNull()
    }

    test("Stdin: beide neuen Felder null") {
        val validator = buildValidator()
        val input = ImportInput.Stdin(
            table = "users",
            input = java.io.ByteArrayInputStream(ByteArray(0)),
        )
        val result = validator.resolveInputContext(
            request = importRequest("json"),
            connectionConfig = sqliteConfig,
            resolvedUrl = resolvedUrl,
            format = DataExportFormat.JSON,
            preparedImport = SchemaPreflightResult(input),
        )
        val ok = result.shouldBeInstance<InputContextResult.Ok>()
        ok.value.bundleExpectedSha256ByTable.shouldBeNull()
        ok.value.singleFileContentSha256.shouldBeNull()
    }
})

private inline fun <reified T> Any.shouldBeInstance(): T {
    check(this is T) { "Expected ${T::class.simpleName}, was ${this::class.simpleName}" }
    return this
}
