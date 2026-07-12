package dev.dmigrate.streaming

import dev.dmigrate.core.validation.requirePositive
import java.nio.file.Path
import java.time.Duration

/**
 * Konfiguration für die [StreamingExporter]-/[StreamingImporter]-Pipeline.
 *
 * LF-008 / LF-010 / LN-012: `chunkSize` bleibt der zentrale
 * user-tunable Parameter. `fetchSize` ist treiberintern und gehört
 * nicht hierher. `parallelism` (LN-007 / LN-008, ADR 0032) ist mit dem
 * parallelen Datenpfad in Scope gekommen; Retry bleibt bewusst draussen,
 * damit `PipelineConfig` nicht zu einem Sammelbehaelter wird.
 *
 * [checkpoint] traegt die Laufzeit-Auspraegung der
 * `pipeline.checkpoint.*`-Config inklusive der aus dem CLI-Flag
 * `--checkpoint-dir` gemergten Quelle. Der Merge selbst
 * passiert zentral in [CheckpointConfig.merge].
 */
data class PipelineConfig(
    /**
     * Anzahl Rows pro Chunk, die der [DataReader][dev.dmigrate.driver.data.DataReader]
     * pro Iteration zurückgeben soll. Default 10 000.
     */
    val chunkSize: Int = 10_000,
    /**
     * LF-013 / LN-012: Laufzeit-Auspraegung der
     * `pipeline.checkpoint.*`-Konfiguration. Ist Checkpointing
     * deaktiviert ([CheckpointConfig.enabled] == false), ignoriert der
     * Streaming-Pfad alle weiteren Felder (Default).
     */
    val checkpoint: CheckpointConfig = CheckpointConfig(),
    /**
     * LN-007 / LN-008 (ADR 0032): maximaler Grad der Nebenläufigkeit für
     * unabhängige Tabellen bzw. Kind-Partitionen eines Parents. Default 1
     * = exakt sequenziell (byte-identisch zum bisherigen Pfad). Der
     * CLI-Wert `--parallel` fliesst hierher; die SQLite-Klemmung auf 1
     * (Pool-Size 1) passiert bereits im Preflight, bevor dieser Wert
     * gesetzt wird.
     */
    val parallelism: Int = 1,
) {
    init {
        require(chunkSize > 0) { "chunkSize must be > 0, got $chunkSize" }
        require(parallelism >= 1) { "parallelism must be >= 1, got $parallelism" }
    }
}

/**
 * LF-013 / LN-012: produktive Laufzeit-Auspraegung der
 * `pipeline.checkpoint.*`-Config. Die Felder fixieren das
 * Name-Mapping zwischen Config-Oberflaeche und Runtime-Typ:
 *
 * - Config-Key `pipeline.checkpoint.enabled` -> [enabled]
 * - Config-Key `pipeline.checkpoint.interval` -> [rowInterval]
 *   (kanonischer row-basierter Trigger)
 * - Config-Key `pipeline.checkpoint.max_interval` -> [maxInterval]
 *   (zeitbasierter Trigger; dokumentiert in `docs/connection-config-spec.md`)
 * - Config-Key `pipeline.checkpoint.directory` -> [directory]
 *
 * Merge-Prioritaet: CLI-Override > Config > Default.
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
     * konfiguriert; Runner lehnen `--resume` dann mit Exit 7 ab.
     */
    val directory: Path? = null,
) {
    init {
        require(rowInterval > 0) { "checkpoint.rowInterval must be > 0, got $rowInterval" }
        requirePositive(maxInterval, "checkpoint.maxInterval")
    }

    companion object {
        /** LN-012: row-basierter Checkpoint-Default. */
        const val DEFAULT_ROW_INTERVAL: Long = 10_000L

        /** LN-012: zeitbasierter Checkpoint-Default. */
        @JvmStatic
        val DEFAULT_MAX_INTERVAL: Duration = Duration.ofMinutes(5)

        /**
         * LF-013 / LN-012: zentraler Merge-Vertrag fuer CLI-Override >
         * Config > Runtime-Default.
 *
         * @param cliDirectory CLI-Wert aus `--checkpoint-dir`.
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
