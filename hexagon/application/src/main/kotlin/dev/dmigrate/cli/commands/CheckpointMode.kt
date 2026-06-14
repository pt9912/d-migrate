package dev.dmigrate.cli.commands

import java.nio.file.Path

/**
 * Review-Finding F2: typsichere Sicht ueber die drei zusammengehoerigen
 * `DataImportRequest`-Felder `noCheckpoint`, `checkpointDir` und `resume`.
 *
 * Die Flat-Fields bleiben aus Backward-Compat-Gruenden im DTO (sonst
 * brechen alle Test-Konstruktoren); die [DataImportRequest.checkpointMode]-
 * Extension-Property liefert daraus die `sealed`-Variante. Konsumenten
 * (ImportCheckpointManager, Validator, Diagnostik) koennen damit ueber
 * `when (mode)` exhaustiv schalten — neue Modi (z.B. read-only-Checkpoint)
 * brauchen nur eine weitere Subklasse und sind compile-time gesehen
 * von allen Konsumenten zu behandeln.
 */
sealed class CheckpointMode {

    /**
     * `--no-checkpoint`: kein Lesen, kein Schreiben, keine
     * Sha256-Berechnung. Mutually exclusive zu `--resume` und
     * `--checkpoint-dir` (per `validateCliFlags`).
     */
    data object Disabled : CheckpointMode()

    /**
     * Default-Modus: Store eingeschaltet. [directory] = explizites
     * `--checkpoint-dir` (sonst kommt es aus `pipeline.checkpoint.directory`).
     * [resume] = `--resume <ref>` oder `null` fuer Fresh-Run.
     */
    data class Enabled(
        val directory: Path?,
        val resume: String?,
    ) : CheckpointMode()
}

/**
 * Klassifiziert die drei Flat-Fields des [DataImportRequest] in die
 * typsichere Sicht. `--resume ""` zaehlt wie "kein Resume" (symmetrisch
 * zu `isNullOrBlank()` in `validateCliFlags`).
 */
val DataImportRequest.checkpointMode: CheckpointMode
    get() = if (noCheckpoint) {
        CheckpointMode.Disabled
    } else {
        CheckpointMode.Enabled(
            directory = checkpointDir,
            resume = resume?.takeIf { it.isNotBlank() },
        )
    }
