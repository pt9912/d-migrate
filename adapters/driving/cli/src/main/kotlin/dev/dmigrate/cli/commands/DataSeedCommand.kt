package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate

/**
 * `d-migrate data seed` — generiert deterministische Testdaten aus einem
 * Schema und importiert sie in eine Zieldatenbank (P1,
 * ImpPlan-1.3.0-cli-data-seed-p1.md). Kein `--rules`, kein
 * `--ai-backend` in dieser Phase (No-Carveouts — beide Flags kommen erst,
 * wenn ihre Phase gebaut ist).
 *
 * Dünne Clikt-Schale: sammelt die CLI-Argumente in [DataSeedOptions] und
 * delegiert an [DataSeedWiring], die die gesamte Geschäftslogik hält.
 */
class DataSeedCommand : CliktCommand(name = "seed") {
    override fun help(context: Context) = "Generate deterministic test data from a schema and import it"

    val schema by option(
        "--schema",
        help = "Schema definition file",
    ).path(mustExist = true).required()

    val target by option(
        "--target",
        help = "Connection URL or named connection from .d-migrate.yaml; " +
            "default from database.default_target in config",
    )

    val count by option(
        "--count",
        help = "Rows per table (default: 100)",
    ).int().default(100)

    val seed by option(
        "--seed",
        help = "Random seed for reproducibility (default: random, printed to stdout)",
    ).long()

    val locale by option(
        "--locale",
        help = "Locale for generated data: en, de (default: en)",
    ).default("en")

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = DataSeedWiring.execute(
            DataSeedOptions(
                schema = schema,
                target = target,
                count = count,
                seed = seed,
                locale = locale,
                configPath = root?.config,
            ),
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
