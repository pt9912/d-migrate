package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.PreGenerationValidator
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.integration.DjangoMigrationExporter
import dev.dmigrate.integration.FlywayMigrationExporter
import dev.dmigrate.integration.KnexMigrationExporter
import dev.dmigrate.integration.LiquibaseMigrationExporter
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolMigrationExporter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

internal data class ToolExportOptions(
    val tool: MigrationTool,
    val source: Path,
    val output: Path,
    val target: String,
    val version: String?,
    val spatialProfile: String?,
    val generateRollback: Boolean,
    val report: Path?,
    val cliContext: CliContext,
)

internal data class ToolExportWiringBundle(
    val schemaReader: (Path) -> SchemaDefinition,
    val generatorLookup: (DatabaseDialect) -> DdlGenerator,
    val preGenerationValidatorLookup: (DatabaseDialect) -> PreGenerationValidator,
    val exporterLookup: (MigrationTool) -> ToolMigrationExporter,
    val existingPathsScanner: (Path) -> Set<String>,
)

internal fun interface ToolExportWiringFactory {
    fun build(): ToolExportWiringBundle
}

internal object DefaultToolExportWiringFactory : ToolExportWiringFactory {

    override fun build() = ToolExportWiringBundle(
        schemaReader = { path -> SchemaFileResolver.codecForPath(path).read(path) },
        generatorLookup = { DatabaseDriverRegistry.get(it).ddlGenerator() },
        preGenerationValidatorLookup = { DatabaseDriverRegistry.get(it).preGenerationValidator() },
        exporterLookup = ::exporterFor,
        existingPathsScanner = ::collectExistingPaths,
    )

    private fun exporterFor(migrationTool: MigrationTool) = when (migrationTool) {
        MigrationTool.FLYWAY -> FlywayMigrationExporter()
        MigrationTool.LIQUIBASE -> LiquibaseMigrationExporter()
        MigrationTool.DJANGO -> DjangoMigrationExporter()
        MigrationTool.KNEX -> KnexMigrationExporter()
    }

    /**
     * Recursively collects relative paths of all regular files under [dir].
     */
    private fun collectExistingPaths(dir: Path): Set<String> {
        if (!dir.isDirectory()) return emptySet()
        return Files.walk(dir).use { stream ->
            stream
                .filter { it.isRegularFile() }
                .map { it.relativeTo(dir).toString().replace('\\', '/') }
                .collect(java.util.stream.Collectors.toSet())
        }
    }
}

/**
 * Shared, Clikt-free wiring for the four `d-migrate export <tool>`
 * subcommands (Flyway, Liquibase, Django, Knex). Each tool-specific
 * Clikt command collects its options and delegates here.
 */
internal object ToolExportWiring {

    fun execute(
        options: ToolExportOptions,
        factory: ToolExportWiringFactory = DefaultToolExportWiringFactory,
    ): Int {
        val bundle = factory.build()
        val request = ToolExportRequest(
            tool = options.tool,
            source = options.source,
            output = options.output,
            target = options.target,
            version = options.version,
            spatialProfile = options.spatialProfile,
            generateRollback = options.generateRollback,
            report = options.report,
            verbose = options.cliContext.verbose,
            quiet = options.cliContext.quiet,
        )
        val runner = ToolExportRunner(
            schemaReader = bundle.schemaReader,
            generatorLookup = bundle.generatorLookup,
            preGenerationValidatorLookup = bundle.preGenerationValidatorLookup,
            exporterLookup = bundle.exporterLookup,
            existingPaths = bundle.existingPathsScanner,
        )
        return runner.execute(request)
    }
}
