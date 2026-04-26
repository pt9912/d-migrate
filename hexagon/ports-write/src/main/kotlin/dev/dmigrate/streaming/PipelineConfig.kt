package dev.dmigrate.streaming

import dev.dmigrate.core.validation.requirePositive
import java.nio.file.Path
import java.time.Duration

/**
 * Konfiguration für die [StreamingExporter]-/[StreamingImporter]-Pipeline.
 *
 * Plan §2.1 / §6.13: `chunkSize` bleibt der zentrale user-tunable
 * Parameter. `fetchSize` ist treiberintern und gehört nicht hierher
 * (Plan §6.13). Parallelism und Retry sind bewusst nicht Teil dieses Typs —
 * Phase B §4.3 haelt den Vertrag eng, damit `PipelineConfig` nicht zu
 * einem Sammelbehaelter wird.
 *
 * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.3): [checkpoint] wird neu
 * eingefuehrt und traegt die Laufzeit-Auspraegung der
 * `pipeline.checkpoint.*`-Config inklusive der aus dem CLI-Flag
 * `--checkpoint-dir` (Phase A) gemergten Quelle. Der Merge selbst
 * passiert zentral in [CheckpointConfig.merge].
 */
data class PipelineConfig(
    /**
     * Anzahl Rows pro Chunk, die der [DataReader][dev.dmigrate.driver.data.DataReader]
     * pro Iteration zurückgeben soll. Default 10 000.
     */
    val chunkSize: Int = 10_000,
    /**
     * 0.9.0 Phase B §4.3: Laufzeit-Auspraegung der
     * `pipeline.checkpoint.*`-Konfiguration. Ist Checkpointing
     * deaktiviert ([CheckpointConfig.enabled] == false), ignoriert der
     * Streaming-Pfad alle weiteren Felder (Default).
     */
    val checkpoint: CheckpointConfig = CheckpointConfig(),
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
    }
}

/**
 * 0.9.0 Phase B §4.3: produktive Laufzeit-Auspraegung der
 * `pipeline.checkpoint.*`-Config. Phase B fixiert die Name-Mapping-
 * Entscheidung zwischen Config-Oberflaeche und Runtime-Typ (§4.3,
 * explizite Namensentscheidung):
 *
 * - Config-Key `pipeline.checkpoint.enabled` -> [enabled]
 * - Config-Key `pipeline.checkpoint.interval` -> [rowInterval]
 *   (kanonischer row-basierter Trigger)
 * - Config-Key `pipeline.checkpoint.max_interval` -> [maxInterval]
 *   (neuer Zeit-Trigger; der Config-Key wird in Phase B festgezogen und
 *   in `docs/connection-config-spec.md` dokumentiert)
 * - Config-Key `pipeline.checkpoint.directory` -> [directory]
 *
 * Merge-Prioritaet (Phase B §4.4): CLI-Override > Config > Default.
 * [merge] liefert den zentralen Merge-Pfad fuer Export und Import, damit
 * keine Runner-spezifische Doppel-Implementierung entsteht.
 */
data class CheckpointConfig(
    val enabled: Boolean = false,
    /**
     * Row-basierter Trigger; produktive Entsprechung des Config-Keys
     * `pipeline.checkpoint.interval`. Default 10 000 Zeilen (LN-012).
     */
    val rowInterval: Long = DEFAULT_ROW_INTERVAL,
    /**
     * Zeit-Trigger; produktive Entsprechung des Config-Keys
     * `pipeline.checkpoint.max_interval`. Default 5 Minuten (LN-012).
     */
    val maxInterval: Duration = DEFAULT_MAX_INTERVAL,
    /**
     * Checkpoint-Verzeichnis; produktive Entsprechung des Config-Keys
     * `pipeline.checkpoint.directory`. `null` = keine Persistenz
     * konfiguriert; Runner lehnen `--resume` dann mit Exit 7 ab (Phase
     * C/D implementiert diese Regel; Phase B etabliert nur den Typ).
     */
    val directory: Path? = null,
) {
    init {
        require(rowInterval > 0) { "checkpoint.rowInterval must be > 0, got $rowInterval" }
        requirePositive(maxInterval, "checkpoint.maxInterval")
    }

    companion object {
        /** LN-012: row-basierter Default (siehe implementation-plan-0.9.0.md §4.4). */
        const val DEFAULT_ROW_INTERVAL: Long = 10_000L

        /** LN-012: time-basierter Default (siehe implementation-plan-0.9.0.md §4.4). */
        @JvmStatic
        val DEFAULT_MAX_INTERVAL: Duration = Duration.ofMinutes(5)

        /**
         * 0.9.0 Phase B §4.4: zentraler Merge-Vertrag fuer
         * CLI-Override > Config > Runtime-Default.
         *
         * @param cliDirectory CLI-Wert aus `--checkpoint-dir` (Phase A).
         *   Ueberschreibt `pipeline.checkpoint.directory`, wenn gesetzt.
         * @param config bereits geparstes Config-Fragment (kann `null`
         *   sein, wenn keine Config-Werte vorliegen).
         * @return effektive [CheckpointConfig] mit aufgeloeste Prioritaet.
         */
        @JvmStatic
        fun merge(
            cliDirectory: Path? = null,
            config: CheckpointConfig? = null,
        ): CheckpointConfig {
            val base = config ?: CheckpointConfig()
            return base.copy(
                directory = cliDirectory ?: base.directory,
            )
        }
    }
}
