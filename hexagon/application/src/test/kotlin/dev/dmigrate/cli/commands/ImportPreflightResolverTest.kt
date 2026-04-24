package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

class ImportPreflightResolverTest : FunSpec({

    fun request(
        target: String? = "sqlite:///tmp/test.db",
        source: String,
        format: String? = null,
        schema: Path? = null,
        table: String? = "users",
    ) = DataImportRequest(
        target = target,
        source = source,
        format = format,
        schema = schema,
        table = table,
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
        quiet = false,
        noProgress = false,
    )

    fun connectionConfig() = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = "/tmp/test.db",
        user = null,
        password = null,
    )

    fun resolver(
        stderr: MutableList<String>,
        targetResolver: (target: String?, configPath: Path?) -> String = { target, _ -> target ?: error("expected target") },
        urlParser: (String) -> ConnectionConfig = { connectionConfig() },
        schemaPreflight: (schemaPath: Path, input: ImportInput, format: DataExportFormat) -> SchemaPreflightResult =
            { _, input, _ -> SchemaPreflightResult(input) },
    ) = ImportPreflightResolver(
        targetResolver = targetResolver,
        urlParser = urlParser,
        schemaPreflight = schemaPreflight,
        stdinProvider = { ByteArrayInputStream("[]".toByteArray()) },
        stderr = stderr::add,
    )

    test("resolve returns preflight context for happy path") {
        val stderr = mutableListOf<String>()
        val sourceFile = Files.createTempFile("dmigrate-import-preflight-", ".json").also {
            Files.writeString(it, """[{"id":1}]""")
        }
        val config = connectionConfig()

        val result = resolver(
            stderr = stderr,
            urlParser = { config },
        ).resolve(
            request(source = sourceFile.toString())
        )

        val context = (result as ImportPreflightResolution.Ok).value
        context.format shouldBe DataExportFormat.JSON
        context.preparedImport shouldBe SchemaPreflightResult(
            ImportInput.SingleFile("users", sourceFile),
        )
        context.charset shouldBe null
        context.resolvedUrl shouldBe "sqlite:///tmp/test.db"
        context.connectionConfig shouldBe config
        stderr shouldBe emptyList()
    }

    test("resolve returns exit 2 when source path does not exist") {
        val stderr = mutableListOf<String>()
        val missingSource = Path.of("/tmp/dmigrate-import-preflight-missing.json")
        Files.deleteIfExists(missingSource)

        val result = resolver(stderr).resolve(
            request(source = missingSource.toString())
        )

        result shouldBe ImportPreflightResolution.Exit(2)
        stderr.single() shouldContain "Source path does not exist"
    }

    test("resolve returns exit 3 when schema preflight fails") {
        val stderr = mutableListOf<String>()
        val sourceFile = Files.createTempFile("dmigrate-import-preflight-", ".json").also {
            Files.writeString(it, """[{"id":1}]""")
        }

        val result = resolver(
            stderr = stderr,
            schemaPreflight = { _, _, _ -> throw ImportPreflightException("schema mismatch") },
        ).resolve(
            request(
                source = sourceFile.toString(),
                schema = Path.of("/tmp/schema.yaml"),
            )
        )

        result shouldBe ImportPreflightResolution.Exit(3)
        stderr.single() shouldContain "schema mismatch"
    }

    test("resolve returns exit 7 when target URL parsing fails") {
        val stderr = mutableListOf<String>()
        val sourceFile = Files.createTempFile("dmigrate-import-preflight-", ".json").also {
            Files.writeString(it, """[{"id":1}]""")
        }

        val result = resolver(
            stderr = stderr,
            urlParser = { throw IllegalArgumentException("bad connection url") },
        ).resolve(
            request(source = sourceFile.toString())
        )

        result shouldBe ImportPreflightResolution.Exit(7)
        stderr.single() shouldContain "bad connection url"
    }
})
