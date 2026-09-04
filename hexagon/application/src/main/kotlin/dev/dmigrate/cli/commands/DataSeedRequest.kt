package dev.dmigrate.cli.commands

import java.nio.file.Path

/**
 * Immutable DTO mit allen CLI-Eingaben für `d-migrate data seed` P1
 * (ImpPlan-1.3.0-cli-data-seed-p1.md). Kein `rules`/`aiBackend` — die
 * Flags existieren in P1 bewusst nicht (No-Carveouts).
 */
data class DataSeedRequest(
    val schema: Path,
    val target: String?,
    val count: Int,
    val seed: Long?,
    val locale: String,
    val cliConfigPath: Path?,
)
