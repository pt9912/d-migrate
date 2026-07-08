package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.ReverseSourceKind
import dev.dmigrate.driver.ReverseSourceRef
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReader
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.util.Comparator

class SchemaReverseWiringTest : FunSpec({

    fun options(
        source: String = "named-reverse",
        output: Path = Path.of("schema.yaml"),
        format: String? = null,
        report: Path? = null,
        includeViews: Boolean = false,
        includeProcedures: Boolean = false,
        includeFunctions: Boolean = false,
        includeTriggers: Boolean = false,
        includeAll: Boolean = false,
        schemaName: String? = null,
        schemaVersion: String? = null,
        cliContext: CliContext = CliContext(quiet = true),
        configPath: Path? = Path.of(".d-migrate-test.yaml"),
    ) = SchemaReverseOptions(
        source = source,
        output = output,
        format = format,
        report = report,
        includeViews = includeViews,
        includeProcedures = includeProcedures,
        includeFunctions = includeFunctions,
        includeTriggers = includeTriggers,
        includeAll = includeAll,
        schemaName = schemaName,
        schemaVersion = schemaVersion,
        cliContext = cliContext,
        configPath = configPath,
    )

    test("wires fake dependencies through successful reverse") {
        val output = Path.of("schema.yaml")
        val configPath = Path.of(".d-migrate-test.yaml")
        val cliContext = CliContext(quiet = true)
        val factory = RecordingSchemaReverseFactory()

        val exit = SchemaReverseWiring.execute(
            options(
                output = output,
                includeViews = true,
                schemaName = "orders",
                schemaVersion = "2.0",
                cliContext = cliContext,
                configPath = configPath,
            ),
            factory,
        )

        exit shouldBe 0
        factory.buildContexts shouldBe listOf(cliContext)
        factory.formatValidations shouldBe listOf(output to "yaml")
        factory.sourceResolutions shouldBe listOf("named-reverse" to configPath)
        factory.parsedUrls shouldBe listOf("sqlite://reverse.db")
        factory.poolConfigs.map { it.dialect } shouldBe listOf(DatabaseDialect.SQLITE)
        factory.driverLookups shouldBe listOf(DatabaseDialect.SQLITE)
        factory.sidecarRequests shouldBe listOf(output to ".report.yaml")
        factory.createdPools.single().closed shouldBe true

        val optionsRead = factory.readOptions.single()
        optionsRead.includeViews shouldBe true
        optionsRead.includeProcedures shouldBe false
        optionsRead.includeFunctions shouldBe false
        optionsRead.includeTriggers shouldBe false

        factory.writtenSchemas.single().path shouldBe output
        factory.writtenSchemas.single().format shouldBe "yaml"
        factory.writtenSchemas.single().schema.name shouldBe "orders"
        factory.writtenSchemas.single().schema.version shouldBe "2.0"
        factory.writtenReports.single().path shouldBe Path.of("schema.report.yaml")
        factory.writtenReports.single().input.result.schema.name shouldBe "orders"
    }

    test("include-all sets all reverse read flags") {
        val factory = RecordingSchemaReverseFactory()

        val exit = SchemaReverseWiring.execute(options(includeAll = true), factory)

        exit shouldBe 0
        val optionsRead = factory.readOptions.single()
        optionsRead.includeViews shouldBe true
        optionsRead.includeProcedures shouldBe true
        optionsRead.includeFunctions shouldBe true
        optionsRead.includeTriggers shouldBe true
    }

    test("explicit report path bypasses default sidecar path") {
        val report = Path.of("reports/reverse.yaml")
        val factory = RecordingSchemaReverseFactory()

        val exit = SchemaReverseWiring.execute(options(report = report), factory)

        exit shouldBe 0
        factory.sidecarRequests shouldBe emptyList()
        factory.writtenReports.single().path shouldBe report
    }

    test("format validation failure returns exit 2 before resolving source") {
        val output = Path.of("schema.txt")
        val factory = RecordingSchemaReverseFactory(
            formatFailure = IllegalArgumentException("format mismatch"),
        )

        val exit = SchemaReverseWiring.execute(
            options(output = output, format = "yaml"),
            factory,
        )

        exit shouldBe 2
        factory.formatValidations shouldBe listOf(output to "yaml")
        factory.sourceResolutions shouldBe emptyList()
        factory.printedErrors.single().first shouldContain "format mismatch"
    }

    test("source resolution failure returns exit 7 before parsing") {
        val factory = RecordingSchemaReverseFactory(
            sourceFailure = RuntimeException("missing alias"),
        )

        val exit = SchemaReverseWiring.execute(options(), factory)

        exit shouldBe 7
        factory.sourceResolutions shouldBe listOf("named-reverse" to Path.of(".d-migrate-test.yaml"))
        factory.parsedUrls shouldBe emptyList()
        factory.printedErrors.single().first shouldContain "Failed to resolve source"
    }

    test("driver lookup failure returns exit 4 and closes the pool") {
        val factory = RecordingSchemaReverseFactory(
            driverFailure = RuntimeException("driver unavailable"),
        )

        val exit = SchemaReverseWiring.execute(options(), factory)

        exit shouldBe 4
        factory.driverLookups shouldBe listOf(DatabaseDialect.SQLITE)
        factory.createdPools.single().closed shouldBe true
        factory.writtenSchemas shouldBe emptyList()
        factory.writtenReports shouldBe emptyList()
        factory.printedErrors.single().first shouldContain "Connection or metadata error"
    }

    test("direct URL parse failure is scrubbed in error output") {
        val source = "postgresql://admin:secret@host/db"
        val factory = RecordingSchemaReverseFactory(
            resolvedUrl = source,
            connectionConfig = ConnectionConfig(DatabaseDialect.POSTGRESQL, "host", null, "db", "admin", "secret"),
            urlParserFailure = RuntimeException("bad secret"),
        )

        val exit = SchemaReverseWiring.execute(options(source = source), factory)

        exit shouldBe 7
        factory.scrubbedInputs.isNotEmpty() shouldBe true
        factory.printedErrors.single().first shouldNotContain "secret"
        factory.printedErrors.single().first shouldContain "***"
        factory.printedErrors.single().second shouldNotContain "secret"
        factory.printedErrors.single().second shouldContain "***"
    }

    test("default factory exposes pure collaborators without opening a pool") {
        val bundle = DefaultSchemaReverseWiringFactory.build(CliContext(quiet = true))

        bundle.sourceResolver("sqlite://reverse.db", null) shouldBe "sqlite://reverse.db"
        bundle.urlParser("sqlite://reverse.db").dialect shouldBe DatabaseDialect.SQLITE
        DatabaseDialect.values().forEach { dialect ->
            bundle.driverLookup(dialect).dialect shouldBe dialect
        }
        bundle.sidecarPath(Path.of("schema.yaml"), ".report.yaml") shouldBe Path.of("schema.report.yaml")
        bundle.formatValidator(Path.of("schema.yaml"), "yaml")

        val scrubbed = bundle.urlScrubber("postgresql://admin:secret@host/db")
        scrubbed shouldNotContain "secret"
        scrubbed shouldContain "***"
    }

    test("default factory schema and report writers create files") {
        val bundle = DefaultSchemaReverseWiringFactory.build(CliContext(quiet = true))
        val output = Files.createTempDirectory("dmigrate-reverse-wiring-")
        val schemaPath = output.resolve("schema.yaml")
        val reportPath = output.resolve("schema.report.yaml")
        val result = SchemaReadResult(SchemaDefinition(name = "reverse_schema", version = "1.0"))
        try {
            bundle.schemaWriter(schemaPath, result.schema, "yaml")
            bundle.reportWriter(
                reportPath,
                SchemaReadReportInput(
                    source = ReverseSourceRef(ReverseSourceKind.ALIAS, "local"),
                    result = result,
                ),
            )

            Files.readString(schemaPath) shouldContain "reverse_schema"
            Files.readString(reportPath) shouldContain "local"
        } finally {
            deleteRecursively(output)
        }
    }
})

private data class WrittenReverseSchema(
    val path: Path,
    val schema: SchemaDefinition,
    val format: String?,
)

private data class WrittenReverseReport(
    val path: Path,
    val input: SchemaReadReportInput,
)

private class RecordingSchemaReverseFactory(
    private val resolvedUrl: String = "sqlite://reverse.db",
    private val connectionConfig: ConnectionConfig = ConnectionConfig(
        DatabaseDialect.SQLITE,
        "localhost",
        null,
        "reverse",
        null,
        null,
    ),
    private val sourceFailure: RuntimeException? = null,
    private val urlParserFailure: RuntimeException? = null,
    private val driverFailure: RuntimeException? = null,
    private val formatFailure: IllegalArgumentException? = null,
) : SchemaReverseWiringFactory {

    val buildContexts = mutableListOf<CliContext>()
    val sourceResolutions = mutableListOf<Pair<String, Path?>>()
    val parsedUrls = mutableListOf<String>()
    val poolConfigs = mutableListOf<ConnectionConfig>()
    val driverLookups = mutableListOf<DatabaseDialect>()
    val writtenSchemas = mutableListOf<WrittenReverseSchema>()
    val writtenReports = mutableListOf<WrittenReverseReport>()
    val sidecarRequests = mutableListOf<Pair<Path, String>>()
    val formatValidations = mutableListOf<Pair<Path, String?>>()
    val scrubbedInputs = mutableListOf<String>()
    val printedErrors = mutableListOf<Pair<String, String>>()
    val readOptions = mutableListOf<SchemaReadOptions>()
    val createdPools = mutableListOf<FakeReversePool>()

    override fun build(cliContext: CliContext): SchemaReverseWiringBundle {
        buildContexts.add(cliContext)
        return SchemaReverseWiringBundle(
            sourceResolver = { source, configPath ->
                sourceResolutions.add(source to configPath)
                sourceFailure?.let { throw it }
                resolvedUrl
            },
            urlParser = { url ->
                parsedUrls.add(url)
                urlParserFailure?.let { throw it }
                connectionConfig
            },
            poolFactory = { config ->
                poolConfigs.add(config)
                FakeReversePool(config.dialect).also { createdPools.add(it) }
            },
            driverLookup = { dialect ->
                driverLookups.add(dialect)
                driverFailure?.let { throw it }
                FakeReverseDriver(dialect, schemaReadResult()) { pool, options ->
                    pool shouldBe createdPools.single()
                    readOptions.add(options)
                }
            },
            schemaWriter = { path, schema, format ->
                writtenSchemas.add(WrittenReverseSchema(path, schema, format))
            },
            reportWriter = { path, input ->
                writtenReports.add(WrittenReverseReport(path, input))
            },
            sidecarPath = { path, suffix ->
                sidecarRequests.add(path to suffix)
                sidecar(path, suffix)
            },
            formatValidator = { path, format ->
                formatValidations.add(path to format)
                formatFailure?.let { throw it }
            },
            urlScrubber = { raw ->
                scrubbedInputs.add(raw)
                raw.replace("secret", "***")
            },
            printError = { message, source ->
                printedErrors.add(message to source)
            },
        )
    }

    private fun schemaReadResult() = SchemaReadResult(
        schema = SchemaDefinition(name = "reverse", version = "0.0.0"),
    )
}

private class FakeReverseDriver(
    override val dialect: DatabaseDialect,
    private val result: SchemaReadResult,
    private val onRead: (ConnectionPool, SchemaReadOptions) -> Unit,
) : DatabaseDriver {
    override fun ddlGenerator() = throw UnsupportedOperationException()
    override fun dataReader() = throw UnsupportedOperationException()
    override fun tableLister() = throw UnsupportedOperationException()
    override fun dataWriter() = throw UnsupportedOperationException()
    override fun urlBuilder() = throw UnsupportedOperationException()
    override fun schemaReader() = object : SchemaReader {
        override fun read(pool: ConnectionPool, options: SchemaReadOptions): SchemaReadResult {
            onRead(pool, options)
            return result
        }
    }
}

private class FakeReversePool(
    override val dialect: DatabaseDialect,
) : ConnectionPool {
    var closed = false

    override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()
    override fun activeConnections() = 0
    override fun close() {
        closed = true
    }
}

private fun sidecar(path: Path, suffix: String): Path {
    val fileName = path.fileName.toString()
    val dotIndex = fileName.lastIndexOf('.')
    val sidecarName = if (dotIndex > 0) {
        "${fileName.substring(0, dotIndex)}$suffix"
    } else {
        "$fileName$suffix"
    }
    return path.parent?.resolve(sidecarName) ?: Path.of(sidecarName)
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
