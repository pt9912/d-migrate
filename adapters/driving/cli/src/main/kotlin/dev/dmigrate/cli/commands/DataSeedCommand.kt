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
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.resolveEffectiveChunkSize
import dev.dmigrate.cli.config.resolveEffectivePoolSettings

/**
 * `d-migrate data seed` — generiert deterministische Testdaten aus einem
 * Schema und importiert sie in eine Zieldatenbank (P1,
 * ImpPlan-1.3.0-cli-data-seed-p1.md; `--rules` kam mit P2,
 * ImpPlan-1.3.0-cli-data-seed-p2.md). Kein `--ai-backend` in dieser Phase
 * (No-Carveouts — das Flag kommt erst, wenn P3 gebaut ist).
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

    val rules by option(
        "--rules",
        help = "Path to a rules file overriding the default generator for matched columns (P2)",
    ).path(mustExist = true)

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        // pool:-/chunk_size-Wiring — `database.pool:`/`pipeline.chunk_size` auflösen (Config > Default).
        // Kein Pipeline-Resolver (kein --chunk-size-Flag bei `seed`), also hier standalone; Config-Fehler → Exit 7.
        val (pool, chunkSize) = try {
            resolveEffectivePoolSettings(root?.config) to resolveEffectiveChunkSize(root?.config, cliChunkSize = null)
        } catch (e: ConfigResolveException) {
            echo("Error: ${e.message}", err = true)
            throw ProgramResult(7)
        }
        val exitCode = DataSeedWiring.execute(
            DataSeedOptions(
                schema = schema,
                target = target,
                count = count,
                seed = seed,
                locale = locale,
                configPath = root?.config,
                pool = pool,
                chunkSize = chunkSize,
                rulesFile = rules,
            ),
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
