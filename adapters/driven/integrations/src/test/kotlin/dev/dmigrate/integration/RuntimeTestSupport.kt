package dev.dmigrate.integration

import dev.dmigrate.cli.commands.ToolExportRequest
import dev.dmigrate.cli.commands.ToolExportRunner
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolMigrationExporter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * Shared infrastructure for Phase E runtime-validation tests.
 * Generates export artifacts via the production [ToolExportRunner] path.
 */
internal object RuntimeTestSupport {

    fun exportArtifacts(
        tool: MigrationTool,
        dialect: String,
        version: String? = null,
        outputDir: Path,
        generateRollback: Boolean = true,
        schemaResource: String = "/fixtures/export-test-schema.yaml",
    ): Int {
        val schemaPath = extractResource(schemaResource, outputDir.parent ?: outputDir)

        val request = ToolExportRequest(
            tool = tool,
            source = schemaPath,
            output = outputDir,
            target = dialect,
            version = version,
            generateRollback = generateRollback,
            verbose = false,
            quiet = true,
        )

        val runner = ToolExportRunner(
            schemaReader = { SchemaFileResolver.codecForPath(it).read(it) },
            validator = { SchemaValidator().validate(it) },
            generatorLookup = { d ->
                // Use real DDL generators from the driver registry
                DatabaseDriverRegistry.get(d).ddlGenerator()
            },
            exporterLookup = { t -> exporterFor(t) },
            mkdirs = { it.toFile().mkdirs() },
            stderr = { /* quiet */ },
        )

        return runner.execute(request)
    }

    private fun exporterFor(tool: MigrationTool): ToolMigrationExporter = when (tool) {
        MigrationTool.FLYWAY -> FlywayMigrationExporter()
        MigrationTool.LIQUIBASE -> LiquibaseMigrationExporter()
        MigrationTool.DJANGO -> DjangoMigrationExporter()
        MigrationTool.KNEX -> KnexMigrationExporter()
    }

    private fun extractResource(resource: String, targetDir: Path): Path {
        val content = RuntimeTestSupport::class.java.getResourceAsStream(resource)
            ?: error("Test resource not found: $resource")
        val target = targetDir.resolve("schema.yaml")
        target.parent.toFile().mkdirs()
        target.writeText(content.bufferedReader().readText())
        return target
    }
}
