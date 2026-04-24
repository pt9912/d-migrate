package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.integration.DjangoMigrationExporter
import dev.dmigrate.integration.FlywayMigrationExporter
import dev.dmigrate.integration.KnexMigrationExporter
import dev.dmigrate.integration.LiquibaseMigrationExporter
import dev.dmigrate.migration.MigrationTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/**
 * `d-migrate export` — group command for tool-specific migration export.
 */
class ExportCommand : CliktCommand(name = "export") {
    override fun help(context: Context) = "Export migration scripts for external tools (Flyway, Liquibase, Django, Knex)"

    init {
        subcommands(
            ExportFlywayCommand(),
            ExportLiquibaseCommand(),
            ExportDjangoCommand(),
            ExportKnexCommand(),
        )
    }

    override fun run() = Unit
}

// ── Shared base for export subcommands ──────────────────────────

private fun CliktCommand.resolveCliContext(): CliContext {
    // export → d-migrate (3 levels: tool → export → d-migrate)
    val root = currentContext.parent?.parent?.parent?.command as? DMigrate
    return root?.cliContext() ?: CliContext()
}

private data class ExportParams(
    val tool: MigrationTool,
    val source: Path,
    val output: Path,
    val target: String,
    val version: String?,
    val spatialProfile: String?,
    val generateRollback: Boolean,
    val report: Path?,
)

private fun CliktCommand.executeExport(params: ExportParams) {
    val ctx = resolveCliContext()
    val request = ToolExportRequest(
        tool = params.tool,
        source = params.source,
        output = params.output,
        target = params.target,
        version = params.version,
        spatialProfile = params.spatialProfile,
        generateRollback = params.generateRollback,
        report = params.report,
        verbose = ctx.verbose,
        quiet = ctx.quiet,
    )
    val runner = ToolExportRunner(
        schemaReader = { path -> SchemaFileResolver.codecForPath(path).read(path) },
        generatorLookup = { DatabaseDriverRegistry.get(it).ddlGenerator() },
        exporterLookup = { migrationTool ->
            when (migrationTool) {
                MigrationTool.FLYWAY -> FlywayMigrationExporter()
                MigrationTool.LIQUIBASE -> LiquibaseMigrationExporter()
                MigrationTool.DJANGO -> DjangoMigrationExporter()
                MigrationTool.KNEX -> KnexMigrationExporter()
            }
        },
        existingPaths = { dir -> collectExistingPaths(dir) },
    )
    val exitCode = runner.execute(request)
    if (exitCode != 0) throw ProgramResult(exitCode)
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

// ── Flyway ──────────────────────────────────────────────────────

class ExportFlywayCommand : CliktCommand(name = "flyway") {
    override fun help(context: Context) =
        "Export Flyway SQL migration files (V/U prefix)"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false).required()
    val output by option("--output", help = "Output directory for migration files")
        .path().required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite)")
        .required()
    val version by option("--version", help = "Migration version (optional, falls back to schema.version)")
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val generateRollback by option("--generate-rollback", help = "Generate Flyway Undo file (U-prefix)")
        .flag()
    val report by option("--report", help = "Report file path (YAML)").path()

    override fun run() = executeExport(ExportParams(
        MigrationTool.FLYWAY, source, output, target, version, spatialProfile, generateRollback, report))
}

// ── Liquibase ───────────────────────────────────────────────────

class ExportLiquibaseCommand : CliktCommand(name = "liquibase") {
    override fun help(context: Context) =
        "Export a Liquibase XML changelog with embedded SQL"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false).required()
    val output by option("--output", help = "Output directory for changelog file")
        .path().required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite)")
        .required()
    val version by option("--version", help = "Migration version (optional, falls back to schema.version)")
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val generateRollback by option("--generate-rollback", help = "Include <rollback> block in changeset")
        .flag()
    val report by option("--report", help = "Report file path (YAML)").path()

    override fun run() = executeExport(ExportParams(
        MigrationTool.LIQUIBASE, source, output, target, version, spatialProfile, generateRollback, report))
}

// ── Django ───────────────────────────────────────────────────────

class ExportDjangoCommand : CliktCommand(name = "django") {
    override fun help(context: Context) =
        "Export a Django RunSQL migration file"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false).required()
    val output by option("--output", help = "Output directory for migration file")
        .path().required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite)")
        .required()
    val version by option("--version", help = "Migration version (required, e.g. 0001 or 0001_initial)")
        .required()
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val generateRollback by option("--generate-rollback", help = "Include reverse_sql in RunSQL")
        .flag()
    val report by option("--report", help = "Report file path (YAML)").path()

    override fun run() = executeExport(ExportParams(
        MigrationTool.DJANGO, source, output, target, version, spatialProfile, generateRollback, report))
}

// ── Knex ─────────────────────────────────────────────────────────

class ExportKnexCommand : CliktCommand(name = "knex") {
    override fun help(context: Context) =
        "Export a Knex.js CommonJS migration file"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false).required()
    val output by option("--output", help = "Output directory for migration file")
        .path().required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite)")
        .required()
    val version by option("--version", help = "Migration version (required, e.g. 20260414120000)")
        .required()
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val generateRollback by option("--generate-rollback", help = "Include exports.down")
        .flag()
    val report by option("--report", help = "Report file path (YAML)").path()

    override fun run() = executeExport(ExportParams(
        MigrationTool.KNEX, source, output, target, version, spatialProfile, generateRollback, report))
}
