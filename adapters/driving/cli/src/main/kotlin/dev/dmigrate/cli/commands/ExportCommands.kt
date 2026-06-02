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
import dev.dmigrate.migration.MigrationTool

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

private fun CliktCommand.resolveCliContext(): CliContext {
    // export → d-migrate (3 levels: tool → export → d-migrate)
    val root = currentContext.parent?.parent?.parent?.command as? DMigrate
    return root?.cliContext() ?: CliContext()
}

private fun CliktCommand.runToolExport(options: ToolExportOptions) {
    val exitCode = ToolExportWiring.execute(options)
    if (exitCode != 0) throw ProgramResult(exitCode)
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

    override fun run() = runToolExport(
        ToolExportOptions(
            tool = MigrationTool.FLYWAY,
            source = source,
            output = output,
            target = target,
            version = version,
            spatialProfile = spatialProfile,
            generateRollback = generateRollback,
            report = report,
            cliContext = resolveCliContext(),
        )
    )
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

    override fun run() = runToolExport(
        ToolExportOptions(
            tool = MigrationTool.LIQUIBASE,
            source = source,
            output = output,
            target = target,
            version = version,
            spatialProfile = spatialProfile,
            generateRollback = generateRollback,
            report = report,
            cliContext = resolveCliContext(),
        )
    )
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

    override fun run() = runToolExport(
        ToolExportOptions(
            tool = MigrationTool.DJANGO,
            source = source,
            output = output,
            target = target,
            version = version,
            spatialProfile = spatialProfile,
            generateRollback = generateRollback,
            report = report,
            cliContext = resolveCliContext(),
        )
    )
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

    override fun run() = runToolExport(
        ToolExportOptions(
            tool = MigrationTool.KNEX,
            source = source,
            output = output,
            target = target,
            version = version,
            spatialProfile = spatialProfile,
            generateRollback = generateRollback,
            report = report,
            cliContext = resolveCliContext(),
        )
    )
}
