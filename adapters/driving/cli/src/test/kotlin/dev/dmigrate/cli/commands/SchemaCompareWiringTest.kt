package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path

class SchemaCompareWiringTest : FunSpec({

    fun options(
        source: String = "file:current.yaml",
        target: String = "file:desired.yaml",
        output: Path? = null,
        cliContext: CliContext = CliContext(quiet = true),
        configPath: Path? = Path.of(".d-migrate-test.yaml"),
    ) = SchemaCompareOptions(
        source = source,
        target = target,
        output = output,
        cliContext = cliContext,
        configPath = configPath,
    )

    test("wires file file operands through fake loaders") {
        val factory = RecordingSchemaCompareFactory()

        val exit = SchemaCompareWiring.execute(options(), factory)

        exit shouldBe 0
        factory.buildContexts shouldBe listOf(CliContext(quiet = true))
        factory.fileLoads shouldBe listOf(Path.of("current.yaml"), Path.of("desired.yaml"))
        factory.dbLoads shouldBe emptyList()
        factory.comparedSchemas shouldBe listOf("current.yaml" to "desired.yaml")
        factory.renderedPlain.single().status shouldBe "identical"
    }

    test("wires file database operands with cli config path") {
        val configPath = Path.of("compare-config.yaml")
        val factory = RecordingSchemaCompareFactory()

        val exit = SchemaCompareWiring.execute(
            options(
                source = "file:current.yaml",
                target = "db:staging",
                configPath = configPath,
            ),
            factory,
        )

        exit shouldBe 0
        factory.fileLoads shouldBe listOf(Path.of("current.yaml"))
        factory.dbLoads shouldBe listOf("staging" to configPath)
        factory.comparedSchemas shouldBe listOf("current.yaml" to "db-staging")
    }

    test("wires database database operands in source target order") {
        val factory = RecordingSchemaCompareFactory()

        val exit = SchemaCompareWiring.execute(
            options(
                source = "db:prod",
                target = "db:staging",
            ),
            factory,
        )

        exit shouldBe 0
        factory.fileLoads shouldBe emptyList()
        factory.dbLoads shouldBe listOf(
            "prod" to Path.of(".d-migrate-test.yaml"),
            "staging" to Path.of(".d-migrate-test.yaml"),
        )
        factory.comparedSchemas shouldBe listOf("db-prod" to "db-staging")
    }

    test("database config phase failure returns exit 7") {
        val factory = RecordingSchemaCompareFactory(
            dbFailure = CompareConfigException("missing alias"),
        )

        val exit = SchemaCompareWiring.execute(
            options(
                source = "file:current.yaml",
                target = "db:staging",
            ),
            factory,
        )

        exit shouldBe 7
        factory.fileLoads shouldBe listOf(Path.of("current.yaml"))
        factory.dbLoads shouldBe listOf("staging" to Path.of(".d-migrate-test.yaml"))
        factory.comparedSchemas shouldBe emptyList()
        factory.renderedPlain shouldBe emptyList()
        factory.printedErrors.single().first shouldContain "Config/URL error"
        factory.printedErrors.single().first shouldContain "missing alias"
    }

    test("database connection phase failure returns exit 4") {
        val factory = RecordingSchemaCompareFactory(
            dbFailure = RuntimeException("connection refused"),
        )

        val exit = SchemaCompareWiring.execute(
            options(
                source = "file:current.yaml",
                target = "db:staging",
            ),
            factory,
        )

        exit shouldBe 4
        factory.dbLoads shouldBe listOf("staging" to Path.of(".d-migrate-test.yaml"))
        factory.comparedSchemas shouldBe emptyList()
        factory.printedErrors.single().first shouldContain "Connection/metadata error"
        factory.printedErrors.single().first shouldContain "connection refused"
    }

    test("cli context output format reaches json renderer") {
        val factory = RecordingSchemaCompareFactory()

        val exit = SchemaCompareWiring.execute(
            options(cliContext = CliContext(outputFormat = "json", quiet = true, verbose = true)),
            factory,
        )

        exit shouldBe 0
        factory.renderedPlain shouldBe emptyList()
        factory.renderedJson.single().status shouldBe "identical"
        factory.renderedJson.single().source shouldBe "file:current.yaml"
        factory.renderedJson.single().target shouldBe "file:desired.yaml"
    }

    test("fake scrubber is used by runner error output") {
        val source = "db:postgresql://admin:secret@host/db"
        val factory = RecordingSchemaCompareFactory(
            dbFailure = CompareConfigException("bad secret"),
        )

        val exit = SchemaCompareWiring.execute(
            options(
                source = source,
                target = "file:desired.yaml",
            ),
            factory,
        )

        exit shouldBe 7
        factory.scrubbedInputs.isNotEmpty() shouldBe true
        factory.printedErrors.single().first shouldNotContain "secret"
        factory.printedErrors.single().first shouldContain "***"
        factory.printedErrors.single().second shouldNotContain "secret"
        factory.printedErrors.single().second shouldContain "***"
    }

    test("default factory file loader reads and validates schema files") {
        val bundle = DefaultSchemaCompareWiringFactory.build(CliContext(quiet = true))
        val schemaPath = Files.createTempFile("dmigrate-compare-wiring-", ".yaml")
        try {
            Files.writeString(
                schemaPath,
                """
                schema_format: "1.0"
                name: "Default Compare"
                version: "1.0.0"
                tables: {}
                """.trimIndent(),
            )

            val operand = bundle.fileLoader(CompareOperand.File(schemaPath))

            operand.reference shouldBe schemaPath.toString()
            operand.schema.name shouldBe "Default Compare"
            operand.validation.isValid.shouldBeTrue()
        } finally {
            Files.deleteIfExists(schemaPath)
        }
    }

    test("default factory wraps config url phase failures") {
        val bundle = DefaultSchemaCompareWiringFactory.build(CliContext(quiet = true))

        val failure = shouldThrow<CompareConfigException> {
            bundle.dbLoader(CompareOperand.Database("oracle://localhost/db"), null)
        }

        failure.message shouldContain "oracle"
    }

    test("default factory exposes renderers comparator and scrubber") {
        val bundle = DefaultSchemaCompareWiringFactory.build(CliContext(quiet = true))
        val doc = SchemaCompareDocument(
            status = "identical",
            exitCode = 0,
            source = "a.yaml",
            target = "b.yaml",
            summary = SchemaCompareSummary(),
            diff = null,
        )

        bundle.comparator(schema("same"), schema("same")).isEmpty() shouldBe true
        bundle.renderJson(doc) shouldContain """"status": "identical""""
        bundle.renderYaml(doc) shouldContain "status: identical"
        bundle.renderPlain(doc) shouldContain "Status: IDENTICAL"

        val scrubbed = bundle.urlScrubber("postgresql://admin:secret@host/db")
        scrubbed shouldNotContain "secret"
        scrubbed shouldContain "***"
    }
})

private class RecordingSchemaCompareFactory(
    private val dbFailure: RuntimeException? = null,
    private val comparatorResult: SchemaDiff = SchemaDiff(),
) : SchemaCompareWiringFactory {

    val buildContexts = mutableListOf<CliContext>()
    val fileLoads = mutableListOf<Path>()
    val dbLoads = mutableListOf<Pair<String, Path?>>()
    val comparedSchemas = mutableListOf<Pair<String, String>>()
    val projectedDiffs = mutableListOf<SchemaDiff>()
    val renderedPlain = mutableListOf<SchemaCompareDocument>()
    val renderedJson = mutableListOf<SchemaCompareDocument>()
    val renderedYaml = mutableListOf<SchemaCompareDocument>()
    val scrubbedInputs = mutableListOf<String>()
    val printedErrors = mutableListOf<Pair<String, String>>()

    override fun build(cliContext: CliContext): SchemaCompareWiringBundle {
        buildContexts.add(cliContext)
        return SchemaCompareWiringBundle(
            fileLoader = { op ->
                fileLoads.add(op.path)
                operand(
                    reference = "file:${op.path}",
                    schemaName = op.path.fileName.toString(),
                )
            },
            dbLoader = { op, configPath ->
                dbLoads.add(op.source to configPath)
                dbFailure?.let { throw it }
                operand(
                    reference = "db:${op.source}",
                    schemaName = "db-${op.source}",
                )
            },
            urlScrubber = { raw ->
                scrubbedInputs.add(raw)
                raw.replace("secret", "***")
            },
            comparator = { left, right ->
                comparedSchemas.add(left.name to right.name)
                comparatorResult
            },
            projectDiff = { diff ->
                projectedDiffs.add(diff)
                DiffView()
            },
            renderPlain = { doc ->
                renderedPlain.add(doc)
                "plain:${doc.status}:${doc.source}:${doc.target}"
            },
            renderJson = { doc ->
                renderedJson.add(doc)
                """{"status":"${doc.status}"}"""
            },
            renderYaml = { doc ->
                renderedYaml.add(doc)
                "status: ${doc.status}"
            },
            printError = { message, source ->
                printedErrors.add(message to source)
            },
        )
    }
}

private fun operand(reference: String, schemaName: String) = ResolvedSchemaOperand(
    reference = reference,
    schema = schema(schemaName),
    validation = ValidationResult(),
)

private fun schema(name: String) = SchemaDefinition(name = name, version = "1.0.0")
