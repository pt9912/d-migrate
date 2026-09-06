package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

/**
 * `d-migrate schema generate` — dünne Clikt-Schale über [SchemaGenerateRunner].
 *
 * Der Command sammelt die CLI-Argumente in einen [SchemaGenerateRequest]
 * und delegiert an [SchemaGenerateRunner]. Die gesamte Verzweigungs-,
 * Formatierungs- und I/O-Koordinierung sitzt im Runner, damit alle
 * Exit-Code-Pfade ohne Clikt und ohne echtes Dateisystem unit-testbar sind
 * — siehe `SchemaGenerateRunnerTest`.
 */
class SchemaGenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Generate database-specific DDL from a schema definition"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false)
        .required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite, mssql, oracle)")
        .required()
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()
    val report by option("--report", help = "Report file path (default: <output>.report.yaml)")
        .path()
    val generateRollback by option("--generate-rollback", help = "Generate rollback DDL")
        .flag()
    val deterministic by option("--deterministic", help = "Omit runtime timestamps from DDL and reports")
        .flag()
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val partitionStorage by option(
        "--partition-storage",
        help = "Storage location for partitioned data (SQL Server: filegroup name; default PRIMARY)",
    )
    val split by option("--split",
        help = "DDL output split mode: 'single' (default) or 'pre-post' for import-friendly artifacts")
        .choice("single", "pre-post")
        .default("single")
    val mysqlNamedSequences by option("--mysql-named-sequences",
        help = "MySQL named-sequence strategy: 'action_required' (default) or 'helper_table' for emulation")
        .choice("action_required", "helper_table")
    val sqliteNamedSequences by option("--sqlite-named-sequences",
        help = "SQLite named-sequence strategy: 'action_required' (default) or 'helper_table' for emulation")
        .choice("action_required", "helper_table")
    val mssqlHashPartitions by option("--mssql-hash-partitions",
        help = "SQL Server hash-partitioning strategy: 'action_required' (default) or 'computed_column' " +
            "to emulate it with a persisted computed column")
        .choice("action_required", "computed_column")

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = SchemaGenerateWiring.execute(
            SchemaGenerateOptions(
                source = source,
                target = target,
                output = output,
                report = report,
                generateRollback = generateRollback,
                deterministic = deterministic,
                spatialProfile = spatialProfile,
                partitionStorage = partitionStorage,
                split = split,
                mysqlNamedSequences = mysqlNamedSequences,
                sqliteNamedSequences = sqliteNamedSequences,
                mssqlHashPartitions = mssqlHashPartitions,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
