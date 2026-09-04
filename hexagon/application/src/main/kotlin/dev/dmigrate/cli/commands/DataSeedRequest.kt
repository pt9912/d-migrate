package dev.dmigrate.cli.commands

import dev.dmigrate.core.seed.SeedRuleSet
import java.nio.file.Path

/**
 * Immutable DTO mit allen CLI-Eingaben für `d-migrate data seed`
 * (ImpPlan-1.3.0-cli-data-seed-p1.md für P1; `rules` kam mit P2 dazu,
 * ImpPlan-1.3.0-cli-data-seed-p2.md). Kein `aiBackend` — das Flag existiert
 * erst in P3 (No-Carveouts).
 */
data class DataSeedRequest(
    val schema: Path,
    val target: String?,
    val count: Int,
    val seed: Long?,
    val locale: String,
    val cliConfigPath: Path?,
    /** Aus `pipeline.chunk_size` aufgelöst (Config > Default 10_000); Zeilen je `DataChunk`. */
    val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    /** Aus `--rules` geladen (P2); `null` = P1-Verhalten unverändert. */
    val rules: SeedRuleSet? = null,
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 10_000
    }
}
