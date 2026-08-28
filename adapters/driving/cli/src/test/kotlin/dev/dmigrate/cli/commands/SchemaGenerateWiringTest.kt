package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.registerDrivers
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.PreGenerationValidator
import dev.dmigrate.driver.SqliteNamedSequenceMode
import dev.dmigrate.driver.mysqlContext
import dev.dmigrate.driver.sqliteContext
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class SchemaGenerateWiringTest : FunSpec({

    // Der Default-Factory-Test loest echte Generatoren ueber die Treiber-
    // Registry auf; ohne eigene Registrierung haenge diese Spec an der
    // Ausfuehrungsreihenfolge anderer Specs (leere Registry bei --tests-Filter).
    beforeSpec { registerDrivers() }

    fun options(
        source: Path = Path.of("schema.yaml"),
        target: String = "postgresql",
        output: Path? = null,
        report: Path? = null,
        generateRollback: Boolean = false,
        deterministic: Boolean = false,
        spatialProfile: String? = null,
        split: String = "single",
        mysqlNamedSequences: String? = null,
        sqliteNamedSequences: String? = null,
        cliContext: CliContext = CliContext(quiet = true),
        partitionStorage: String? = null,
        configPath: Path? = null,
    ) = SchemaGenerateOptions(
        source = source,
        target = target,
        output = output,
        report = report,
        generateRollback = generateRollback,
        deterministic = deterministic,
        partitionStorage = partitionStorage,
        spatialProfile = spatialProfile,
        split = split,
        mysqlNamedSequences = mysqlNamedSequences,
        sqliteNamedSequences = sqliteNamedSequences,
        mssqlHashPartitions = null,
        cliContext = cliContext,
        configPath = configPath,
    )

    fun ddlConfig(storage: String): Path {
        val file = Files.createTempFile("dmigrate-generate-ddlcfg-", ".yaml")
        Files.writeString(
            file,
            """
            ddl:
              mssql:
                partition_storage: $storage
            """.trimIndent(),
        )
        return file
    }

    test("ddl.mssql.partition_storage from the config file reaches the generation options") {
        val factory = RecordingSchemaGenerateFactory()

        val exit = SchemaGenerateWiring.execute(
            options(target = "mssql", configPath = ddlConfig("FG_DATA")),
            factory,
        )

        exit shouldBe 0
        factory.preGenerationOptions.single().partitionStorage shouldBe "FG_DATA"
    }

    test("an explicit --partition-storage beats the config file") {
        val factory = RecordingSchemaGenerateFactory()

        val exit = SchemaGenerateWiring.execute(
            options(target = "mssql", partitionStorage = "FG_CLI", configPath = ddlConfig("FG_DATA")),
            factory,
        )

        exit shouldBe 0
        factory.preGenerationOptions.single().partitionStorage shouldBe "FG_CLI"
    }

    // `ddl.mssql.*` ist nach Dialekt geschachtelt: ein anderes Ziel darf den
    // Wert nicht erben, sonst waere die Schachtelung nur Dekoration.
    test("a non-mssql target ignores the mssql sub-block") {
        val factory = RecordingSchemaGenerateFactory()

        val exit = SchemaGenerateWiring.execute(
            options(target = "postgresql", configPath = ddlConfig("FG_DATA")),
            factory,
        )

        exit shouldBe 0
        factory.preGenerationOptions.single().partitionStorage shouldBe DdlGenerationOptions().partitionStorage
    }

    test("an invalid ddl value exits 7 instead of silently generating with PRIMARY") {
        val factory = RecordingSchemaGenerateFactory()

        val exit = SchemaGenerateWiring.execute(
            options(target = "mssql", configPath = ddlConfig("\"FG]); DROP TABLE [orders\"")),
            factory,
        )

        exit shouldBe 7
        factory.printedErrors.single().first shouldContain "plain identifier"
        factory.generatorLookups.shouldBeEmpty()
    }

    test("wires single output through fake generator and report writer") {
        val output = Files.createTempDirectory("dmigrate-generate-single-")
        val ddl = output.resolve("schema.sql")
        val factory = RecordingSchemaGenerateFactory()
        try {
            val exit = SchemaGenerateWiring.execute(options(output = ddl), factory)

            exit shouldBe 0
            factory.buildContexts shouldBe listOf(CliContext(quiet = true))
            factory.schemaReads shouldBe listOf(Path.of("schema.yaml"))
            factory.generatorLookups shouldBe listOf(DatabaseDialect.POSTGRESQL)
            factory.preGenerationLookups shouldBe listOf(DatabaseDialect.POSTGRESQL)
            factory.preGenerationOptions.single().deterministic shouldBe false
            factory.generators.single().generateCalls shouldBe 1
            factory.generators.single().rollbackCalls shouldBe 0
            factory.sidecarRequests shouldBe listOf(ddl to ".report.yaml")
            factory.reportWrites.single().path shouldBe output.resolve("schema.report.yaml")
            factory.reportWrites.single().splitMode shouldBe null
            Files.readString(ddl) shouldContain "CREATE TABLE generated"
        } finally {
            deleteRecursively(output)
        }
    }

    test("pre-post split writes phase files and enables deferred foreign keys") {
        val output = Files.createTempDirectory("dmigrate-generate-split-")
        val ddl = output.resolve("schema.sql")
        val factory = RecordingSchemaGenerateFactory(
            result = DdlResult(
                statements = listOf(
                    DdlStatement("CREATE TABLE generated (id INT);", phase = DdlPhase.PRE_DATA),
                    DdlStatement("CREATE VIEW generated_v AS SELECT id FROM generated;", phase = DdlPhase.POST_DATA),
                ),
            ),
        )
        try {
            val exit = SchemaGenerateWiring.execute(
                options(output = ddl, split = "pre-post"),
                factory,
            )

            exit shouldBe 0
            factory.splitPathRequests shouldBe listOf(
                ddl to DdlPhase.PRE_DATA,
                ddl to DdlPhase.POST_DATA,
            )
            factory.reportWrites.single().splitMode shouldBe "pre-post"
            factory.generators.single().generateOptions.single().deferForeignKeys shouldBe true
            Files.readString(output.resolve("schema.pre-data.sql")) shouldContain "CREATE TABLE generated"
            Files.readString(output.resolve("schema.post-data.sql")) shouldContain "CREATE VIEW generated_v"
        } finally {
            deleteRecursively(output)
        }
    }

    test("pre-post split keeps foreign keys inline for a generator without deferral support") {
        val output = Files.createTempDirectory("dmigrate-generate-split-nodefer-")
        val ddl = output.resolve("schema.sql")
        val factory = RecordingSchemaGenerateFactory(deferredForeignKeysSupported = false)
        try {
            val exit = SchemaGenerateWiring.execute(options(output = ddl, split = "pre-post"), factory)

            exit shouldBe 0
            // Die Deferral-Entscheidung folgt der Port-Faehigkeit (supportsDeferredForeignKeys),
            // nicht mehr einem fest verdrahteten Dialekt.
            factory.generators.single().generateOptions.single().deferForeignKeys shouldBe false
            factory.generatorLookups shouldBe listOf(DatabaseDialect.POSTGRESQL)
        } finally {
            deleteRecursively(output)
        }
    }

    test("generate rollback writes rollback file and calls rollback generator") {
        val output = Files.createTempDirectory("dmigrate-generate-rollback-")
        val ddl = output.resolve("schema.sql")
        val factory = RecordingSchemaGenerateFactory()
        try {
            val exit = SchemaGenerateWiring.execute(
                options(output = ddl, generateRollback = true),
                factory,
            )

            exit shouldBe 0
            factory.rollbackPathRequests shouldBe listOf(ddl)
            factory.generators.single().rollbackCalls shouldBe 1
            Files.readString(output.resolve("schema.rollback.sql")) shouldContain "DROP TABLE generated"
        } finally {
            deleteRecursively(output)
        }
    }

    test("deterministic and mysql named sequence options reach generator") {
        val output = Files.createTempDirectory("dmigrate-generate-mysql-")
        val factory = RecordingSchemaGenerateFactory()
        try {
            val exit = SchemaGenerateWiring.execute(
                options(
                    target = "mysql",
                    output = output.resolve("schema.sql"),
                    deterministic = true,
                    mysqlNamedSequences = "helper_table",
                ),
                factory,
            )

            exit shouldBe 0
            val generatedOptions = factory.generators.single().generateOptions.single()
            generatedOptions.deterministic shouldBe true
            generatedOptions.mysqlContext?.namedSequenceMode shouldBe MysqlNamedSequenceMode.HELPER_TABLE
            factory.reportWrites.single().options.deterministic shouldBe true
        } finally {
            deleteRecursively(output)
        }
    }

    test("sqlite named sequence options reach generator") {
        val output = Files.createTempDirectory("dmigrate-generate-sqlite-")
        val factory = RecordingSchemaGenerateFactory()
        try {
            val exit = SchemaGenerateWiring.execute(
                options(
                    target = "sqlite",
                    output = output.resolve("schema.sql"),
                    sqliteNamedSequences = "helper_table",
                ),
                factory,
            )

            exit shouldBe 0
            factory.generators.single().generateOptions.single()
                .sqliteContext?.namedSequenceMode shouldBe SqliteNamedSequenceMode.HELPER_TABLE
        } finally {
            deleteRecursively(output)
        }
    }

    test("pre-generation validation failure returns exit 3 before generator lookup") {
        val output = Files.createTempDirectory("dmigrate-generate-pregen-")
        val factory = RecordingSchemaGenerateFactory(
            preGenerationErrors = listOf(ValidationError("E999", "blocked", "tables.generated")),
        )
        try {
            val exit = SchemaGenerateWiring.execute(
                options(output = output.resolve("schema.sql")),
                factory,
            )

            exit shouldBe 3
            factory.preGenerationLookups shouldBe listOf(DatabaseDialect.POSTGRESQL)
            factory.generatorLookups shouldBe emptyList()
            factory.validationPrints.single().validationResult.errors.single().code shouldBe "E999"
            Files.exists(output.resolve("schema.sql")) shouldBe false
        } finally {
            deleteRecursively(output)
        }
    }

    test("invalid target uses injected error formatter before reading schema") {
        val factory = RecordingSchemaGenerateFactory()

        val exit = SchemaGenerateWiring.execute(options(target = "oracle"), factory)

        exit shouldBe 2
        factory.printedErrors.single().first shouldContain "oracle"
        factory.schemaReads shouldBe emptyList()
        factory.generatorLookups shouldBe emptyList()
    }

    test("default factory exposes schema reader, drivers, helpers, json formatter and report writer") {
        val bundle = DefaultSchemaGenerateWiringFactory.build(CliContext(quiet = true))
        val output = Files.createTempDirectory("dmigrate-generate-default-")
        val schemaPath = output.resolve("schema.yaml")
        val reportPath = output.resolve("schema.report.yaml")
        try {
            Files.writeString(
                schemaPath,
                """
                schema_format: "1.0"
                name: "Default Generate"
                version: "1.0.0"
                tables: {}
                """.trimIndent(),
            )
            val schema = bundle.schemaReader(schemaPath)
            val result = DdlResult(listOf(DdlStatement("CREATE TABLE generated (id INT);")))

            schema.name shouldBe "Default Generate"
            DatabaseDialect.entries.forEach { dialect ->
                bundle.generatorLookup(dialect).dialect shouldBe dialect
                bundle.preGenerationValidatorLookup(dialect).validate(schema, DdlGenerationOptions()).isEmpty()
                    .shouldBeTrue()
            }
            bundle.sidecarPath(output.resolve("schema.sql"), ".report.yaml") shouldBe reportPath
            bundle.rollbackPath(output.resolve("schema.sql")) shouldBe output.resolve("schema.rollback.sql")
            bundle.splitPath(output.resolve("schema.sql"), DdlPhase.PRE_DATA) shouldBe output.resolve("schema.pre-data.sql")
            bundle.formatJsonOutput(result, schema, "postgresql", SplitMode.SINGLE, null, null) shouldContain
                "\"target\": \"postgresql\""

            bundle.reportWriter(
                reportPath,
                result,
                schema,
                "postgresql",
                schemaPath,
                null,
                DdlGenerationOptions(deterministic = true),
            )
            Files.readString(reportPath) shouldContain "Default Generate"
        } finally {
            deleteRecursively(output)
        }
    }

    test("mssql file output is a GO-separated script while the generator result stays batch-free") {
        val output = Files.createTempDirectory("dmigrate-generate-mssql-")
        val ddl = output.resolve("schema.sql")
        val factory = RecordingSchemaGenerateFactory(
            result = DdlResult(
                statements = listOf(
                    DdlStatement("CREATE TABLE [generated] ([id] INT);"),
                    DdlStatement("CREATE OR ALTER VIEW [v] AS SELECT [id] FROM [generated];"),
                ),
            ),
        )
        try {
            SchemaGenerateWiring.execute(options(output = ddl, target = "mssql"), factory) shouldBe 0
            val script = Files.readString(ddl)
            script shouldContain "CREATE TABLE [generated] ([id] INT);\nGO\n"
            script shouldContain "CREATE OR ALTER VIEW [v] AS SELECT [id] FROM [generated];\nGO\n"
            factory.generators.single().dialect shouldBe DatabaseDialect.MSSQL
        } finally {
            deleteRecursively(output)
        }
    }
})

private data class GenerateReportWrite(
    val path: Path,
    val result: DdlResult,
    val schema: SchemaDefinition,
    val dialect: String,
    val source: Path,
    val splitMode: String?,
    val options: DdlGenerationOptions,
)

private data class ValidationPrint(
    val validationResult: ValidationResult,
    val schema: SchemaDefinition,
    val source: String,
)

private class RecordingSchemaGenerateFactory(
    private val result: DdlResult = DdlResult(
        statements = listOf(DdlStatement("CREATE TABLE generated (id INT);")),
    ),
    private val rollbackResult: DdlResult = DdlResult(
        statements = listOf(DdlStatement("DROP TABLE generated;")),
    ),
    private val preGenerationErrors: List<ValidationError> = emptyList(),
    private val deferredForeignKeysSupported: Boolean = true,
) : SchemaGenerateWiringFactory {

    val buildContexts = mutableListOf<CliContext>()
    val schemaReads = mutableListOf<Path>()
    val generatorLookups = mutableListOf<DatabaseDialect>()
    val preGenerationLookups = mutableListOf<DatabaseDialect>()
    val preGenerationOptions = mutableListOf<DdlGenerationOptions>()
    val reportWrites = mutableListOf<GenerateReportWrite>()
    val sidecarRequests = mutableListOf<Pair<Path, String>>()
    val rollbackPathRequests = mutableListOf<Path>()
    val splitPathRequests = mutableListOf<Pair<Path, DdlPhase>>()
    val printedErrors = mutableListOf<Pair<String, String>>()
    val validationPrints = mutableListOf<ValidationPrint>()
    val generators = mutableListOf<FakeGenerateGenerator>()

    override fun build(cliContext: CliContext): SchemaGenerateWiringBundle {
        buildContexts.add(cliContext)
        return SchemaGenerateWiringBundle(
            schemaReader = { path ->
                schemaReads.add(path)
                validGenerateSchema()
            },
            generatorLookup = { dialect ->
                generatorLookups.add(dialect)
                FakeGenerateGenerator(dialect, result, rollbackResult, deferredForeignKeysSupported)
                    .also { generators.add(it) }
            },
            preGenerationValidatorLookup = { dialect ->
                preGenerationLookups.add(dialect)
                object : PreGenerationValidator {
                    override fun validate(
                        schema: SchemaDefinition,
                        options: DdlGenerationOptions,
                    ): List<ValidationError> {
                        preGenerationOptions.add(options)
                        return preGenerationErrors
                    }
                }
            },
            reportWriter = { path, result, schema, dialect, source, splitMode, options ->
                reportWrites.add(GenerateReportWrite(path, result, schema, dialect, source, splitMode, options))
            },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = { path, suffix ->
                sidecarRequests.add(path to suffix)
                SchemaGenerateHelpers.sidecarPath(path, suffix)
            },
            rollbackPath = { path ->
                rollbackPathRequests.add(path)
                SchemaGenerateHelpers.rollbackPath(path)
            },
            splitPath = { path, phase ->
                splitPathRequests.add(path to phase)
                SchemaGenerateHelpers.splitPath(path, phase)
            },
            printError = { message, source ->
                printedErrors.add(message to source)
            },
            printValidationResult = { validationResult, schema, source ->
                validationPrints.add(ValidationPrint(validationResult, schema, source))
            },
        )
    }
}

private class FakeGenerateGenerator(
    override val dialect: DatabaseDialect,
    private val result: DdlResult,
    private val rollbackResult: DdlResult,
    override val supportsDeferredForeignKeys: Boolean = true,
) : DdlGenerator {
    val generateOptions = mutableListOf<DdlGenerationOptions>()
    val rollbackOptions = mutableListOf<DdlGenerationOptions>()
    val generateCalls: Int get() = generateOptions.size
    val rollbackCalls: Int get() = rollbackOptions.size

    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        generateOptions.add(options)
        return result
    }

    override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        rollbackOptions.add(options)
        return rollbackResult
    }
}

private fun validGenerateSchema() = SchemaDefinition(
    name = "Generated",
    version = "1.0.0",
    tables = mapOf(
        "generated" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true), required = true),
            ),
            primaryKey = listOf("id"),
        ),
    ),
)

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
