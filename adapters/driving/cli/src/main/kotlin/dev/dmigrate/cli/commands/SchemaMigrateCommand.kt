package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.driver.DatabaseDialect

class SchemaMigrateCommand : CliktCommand(name = "migrate") {
    override fun help(context: Context) =
        "Plan and optionally execute a migration from current to desired schema"

    val source by option("--source", help = "Soll-Schema (file)").required()
    val target by option("--target", help = "Ist-state operand: file:<current.yaml> or db:<url-or-alias>")
        .required()
    val dialectFlag by option("--dialect", help = "Target dialect (required for file targets)")
        .choice(
            "postgresql" to DatabaseDialect.POSTGRESQL,
            "mysql" to DatabaseDialect.MYSQL,
            "sqlite" to DatabaseDialect.SQLITE,
            "mssql" to DatabaseDialect.MSSQL,
        )
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val output by option("--output", help = "Up-SQL output file").path()
    val rollbackOutput by option("--rollback-output", help = "Down-SQL output file").path()
    val report by option("--report", help = "Report output file (required with --execute)").path()
    val planArtefact by option(
        "--plan-artefact",
        help = "Signed migration-plan.v1 artefact output path (canonical JSON; emitted alongside " +
            "--report). The artefact captures operations, diagnostics, reversibility, rendered " +
            "statement hashes and the F.4 rename-projection contract under the rename-projections.v1 " +
            "semantic-extension gate.",
    ).path()
    val reportFormat by option("--report-format", help = "Report format")
        .choice("json", "yaml").default("json")
    val planOnly by option("--plan-only", help = "Only render the plan / report; no SQL output").flag()
    val allowDestructive by option("--allow-destructive", help = "Permit destructive Up operations").flag()
    val allowExtensionInstall by option(
        "--allow-extension-install",
        help = "Permit PostgreSQL CREATE EXTENSION prerequisites for extension-dependent migrations",
    ).flag()
    val migrationOverlays by option(
        "--migration-overlay",
        help = "Versioned migration overlay JSON file (repeatable)",
    ).path(mustExist = true, canBeDir = false, mustBeReadable = true).multiple()
    val renameTableFlags by option(
        "--rename-table",
        help = "Inline rename mapping <from>:<to> (repeatable). Equivalent to a single-entry " +
            "migration-overlay rename-mapping; not artefact-stable, use --migration-overlay for " +
            "long-lived plans.",
    ).multiple()
    val renameColumnFlags by option(
        "--rename-column",
        help = "Inline column rename mapping <table>.<from>:<table>.<to> (repeatable). Both sides " +
            "must use the same table prefix; use --migration-overlay for cross-table renames.",
    ).multiple()
    val generateRollback by option("--generate-rollback", help = "Render Down-SQL alongside Up").flag()
    val execute by option("--execute", help = "Execute Up-SQL against --target (DB only)").flag()
    val dryRun by option("--dry-run", help = "Plan/SQL only, do not execute").flag()
    val debugBody by option(
        "--debug-body",
        help = "UNSAFE: emit unmasked routine bodies in the report display plane. " +
            "Execution-Plane (SQL output) is unchanged. Default is scrubbed-only.",
    ).flag()
    val routineCapabilityFlags by option(
        "--routine-capability",
        help = "Override per-routine-kind capability for stored functions/procedures (repeatable). " +
            "Format: '<kind>:<key>=<value>[,<key>=<value>...]'. Kinds: function, procedure. " +
            "Keys: enabled (true|false), minServerVersion (major.minor.patch, MySQL/MariaDB only). " +
            "CLI takes precedence over the '.d-migrate.yaml' 'routineCapability:' section and over " +
            "the dialect/server-version defaults. Structurally invalid values are surfaced as " +
            "ROUTINE_CAPABILITY_CONFIG_INVALID in the migration report.",
    ).multiple()
    val strictGapOperations by option(
        "--strict-gap-operations",
        help = "Block operations that render with a multi-statement visibility gap " +
            "(e.g. ReplaceTrigger via Drop+Create on PostgreSQL < 14, or MySQL/SQLite where no " +
            "native `CREATE OR REPLACE TRIGGER` exists). Default off — the lenient path emits the " +
            "fallback statements and surfaces the gap as a W_TRIGGER_REPLACE_GAP warning.",
    ).flag()
    val sqliteNamedSequences by option(
        "--sqlite-named-sequences",
        help = "SQLite named-sequence strategy: 'action_required' (default) or 'helper_table' to " +
            "opt into the dmg_sequences emulation. Required for `preserveCurrentValue` on SQLite " +
            "targets — without it, candidate sequence ops block with " +
            "SEQUENCE_PRESERVE_OPT_IN_REQUIRED.",
    ).choice("action_required", "helper_table")
    val mssqlHashPartitions by option(
        "--mssql-hash-partitions",
        help = "SQL Server hash-partitioning strategy: 'action_required' (default) or 'computed_column' " +
            "to emulate it with a persisted computed column. Mirrors the flag on `schema generate`.",
    ).choice("action_required", "computed_column")
    val lockTimeoutMs by option(
        "--lock-timeout-ms",
        help = "Atomic-preserve lock-timeout budget in milliseconds. Optional; defaults to " +
            "the server's per-dialect default (CLI: 5000). Valid range [10, 60000]. Out-of-range " +
            "values exit 2 before the pipeline runs.",
    ).long()
    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = SchemaMigrateWiring.execute(
            SchemaMigrateOptions(
                source = source,
                target = target,
                dialect = dialectFlag,
                spatialProfile = spatialProfile,
                output = output,
                rollbackOutput = rollbackOutput,
                report = report,
                planArtefact = planArtefact,
                reportFormat = reportFormat,
                planOnly = planOnly,
                allowDestructive = allowDestructive,
                allowExtensionInstall = allowExtensionInstall,
                migrationOverlays = migrationOverlays,
                renameTableFlags = renameTableFlags,
                renameColumnFlags = renameColumnFlags,
                generateRollback = generateRollback,
                execute = execute,
                dryRun = dryRun,
                debugBody = debugBody,
                routineCapabilityFlags = routineCapabilityFlags,
                strictGapOperations = strictGapOperations,
                sqliteNamedSequences = sqliteNamedSequences,
                mssqlHashPartitions = mssqlHashPartitions,
                lockTimeoutMs = lockTimeoutMs,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
