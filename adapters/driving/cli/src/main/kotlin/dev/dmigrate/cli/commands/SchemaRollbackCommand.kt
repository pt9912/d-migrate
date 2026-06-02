package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

class SchemaRollbackCommand : CliktCommand(name = "rollback") {
    override fun help(context: Context) =
        "Validate and execute a Down-SQL artefact produced by `schema migrate --generate-rollback`"

    val source by option("--source", help = "Down-SQL artefact path")
        .path().required()
    val target by option("--target", help = "Target DB connection (db:<url-or-alias>)")
        .required()
    val execute by option("--execute", help = "Execute Down-SQL against --target").flag()
    val allowDestructive by option("--allow-destructive", help = "Permit destructive Down operations").flag()
    val allowPartialRollback by option(
        "--allow-partial-rollback",
        help = "Permit execution of an explicitly partial rollback artefact",
    ).flag()
    val dryRun by option("--dry-run", help = "Validate / preview only").flag()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = SchemaRollbackWiring.execute(
            SchemaRollbackOptions(
                source = source,
                target = target,
                execute = execute,
                allowDestructive = allowDestructive,
                allowPartialRollback = allowPartialRollback,
                dryRun = dryRun,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
