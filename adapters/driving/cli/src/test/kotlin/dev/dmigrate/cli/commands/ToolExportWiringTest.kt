package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.PreGenerationValidator
import dev.dmigrate.migration.ArtifactRelativePath
import dev.dmigrate.migration.MigrationArtifact
import dev.dmigrate.migration.MigrationBundle
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolExportResult
import dev.dmigrate.migration.ToolMigrationExporter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

class ToolExportWiringTest : FunSpec({

    fun options(
        tool: MigrationTool,
        output: Path,
        version: String = versionFor(tool),
        generateRollback: Boolean = false,
    ) = ToolExportOptions(
        tool = tool,
        source = Path.of("schema.yaml"),
        output = output,
        target = "postgresql",
        version = version,
        spatialProfile = null,
        generateRollback = generateRollback,
        report = null,
        cliContext = CliContext(quiet = true),
    )

    context("happy path by migration tool") {
        MigrationTool.values().forEach { tool ->
            test("wires fake exporter for ${tool.name.lowercase()}") {
                val output = Files.createTempDirectory("dmigrate-tool-export-${tool.name.lowercase()}-")
                val factory = RecordingToolExportFactory()
                try {
                    val exit = ToolExportWiring.execute(options(tool, output), factory)

                    exit shouldBe 0
                    factory.buildCalls shouldBe 1
                    factory.schemaReads shouldBe listOf(Path.of("schema.yaml"))
                    factory.generatorLookups shouldBe listOf(DatabaseDialect.POSTGRESQL)
                    factory.preGenerationLookups shouldBe listOf(DatabaseDialect.POSTGRESQL)
                    factory.exporterLookups shouldBe listOf(tool)
                    factory.existingPathScans shouldBe listOf(output)
                    factory.renderedTools shouldBe listOf(tool)
                    Files.readString(output.resolve("${tool.name.lowercase()}.sql")) shouldContain
                        "exported ${tool.name.lowercase()}"
                } finally {
                    deleteRecursively(output)
                }
            }
        }
    }

    test("existing path collision comes from injected scanner and blocks writes") {
        val output = Files.createTempDirectory("dmigrate-tool-export-collision-")
        val factory = RecordingToolExportFactory(
            existingPaths = setOf("flyway.sql"),
        )
        try {
            val exit = ToolExportWiring.execute(options(MigrationTool.FLYWAY, output), factory)

            exit shouldBe 7
            factory.existingPathScans shouldBe listOf(output)
            factory.renderedTools shouldBe listOf(MigrationTool.FLYWAY)
            Files.exists(output.resolve("flyway.sql")) shouldBe false
        } finally {
            deleteRecursively(output)
        }
    }

    test("rollback flag reaches generator before exporter rendering") {
        val output = Files.createTempDirectory("dmigrate-tool-export-rollback-")
        val factory = RecordingToolExportFactory()
        try {
            val exit = ToolExportWiring.execute(
                options(MigrationTool.LIQUIBASE, output, generateRollback = true),
                factory,
            )

            exit shouldBe 0
            factory.rollbackGenerations shouldBe 1
            factory.renderedTools shouldBe listOf(MigrationTool.LIQUIBASE)
        } finally {
            deleteRecursively(output)
        }
    }

    test("default factory creates exporters for every migration tool") {
        val bundle = DefaultToolExportWiringFactory.build()

        MigrationTool.values().forEach { tool ->
            bundle.exporterLookup(tool).tool shouldBe tool
        }
    }

    test("default factory scans existing paths recursively with normalized separators") {
        val output = Files.createTempDirectory("dmigrate-tool-export-scan-")
        try {
            Files.createDirectories(output.resolve("nested"))
            Files.writeString(output.resolve("root.sql"), "-- root")
            Files.writeString(output.resolve("nested/child.sql"), "-- child")

            DefaultToolExportWiringFactory.build()
                .existingPathsScanner(output)
                .shouldContainExactlyInAnyOrder("root.sql", "nested/child.sql")
        } finally {
            deleteRecursively(output)
        }
    }
})

private fun versionFor(tool: MigrationTool): String = when (tool) {
    MigrationTool.FLYWAY -> "1.0"
    MigrationTool.LIQUIBASE -> "1.0"
    MigrationTool.DJANGO -> "0001"
    MigrationTool.KNEX -> "20260414120000"
}

private class RecordingToolExportFactory(
    private val existingPaths: Set<String> = emptySet(),
) : ToolExportWiringFactory {

    var buildCalls = 0
    val schemaReads = mutableListOf<Path>()
    val generatorLookups = mutableListOf<DatabaseDialect>()
    val preGenerationLookups = mutableListOf<DatabaseDialect>()
    val exporterLookups = mutableListOf<MigrationTool>()
    val existingPathScans = mutableListOf<Path>()
    val renderedTools = mutableListOf<MigrationTool>()
    var rollbackGenerations = 0

    override fun build(): ToolExportWiringBundle {
        buildCalls++
        return ToolExportWiringBundle(
            schemaReader = { path ->
                schemaReads.add(path)
                SchemaDefinition(name = "Shop", version = "1.0")
            },
            generatorLookup = { dialect ->
                generatorLookups.add(dialect)
                FakeToolExportGenerator(dialect) { rollbackGenerations++ }
            },
            preGenerationValidatorLookup = { dialect ->
                preGenerationLookups.add(dialect)
                PreGenerationValidator.NoOp
            },
            exporterLookup = { tool ->
                exporterLookups.add(tool)
                RecordingExporter(tool) { renderedTools.add(it) }
            },
            existingPathsScanner = { output ->
                existingPathScans.add(output)
                existingPaths
            },
        )
    }
}

private class FakeToolExportGenerator(
    override val dialect: DatabaseDialect,
    private val onRollback: () -> Unit,
) : DdlGenerator {
    override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions) =
        DdlResult(listOf(DdlStatement("CREATE TABLE ${schema.name.lowercase()} (id INTEGER);")))

    override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions): DdlResult {
        onRollback()
        return DdlResult(listOf(DdlStatement("DROP TABLE ${schema.name.lowercase()};")))
    }
}

private class RecordingExporter(
    override val tool: MigrationTool,
    private val onRender: (MigrationTool) -> Unit,
) : ToolMigrationExporter {
    override fun render(bundle: MigrationBundle): ToolExportResult {
        onRender(tool)
        return ToolExportResult(
            artifacts = listOf(
                MigrationArtifact(
                    relativePath = ArtifactRelativePath.of("${tool.name.lowercase()}.sql"),
                    kind = "fake",
                    content = "-- exported ${tool.name.lowercase()}",
                ),
            ),
        )
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
