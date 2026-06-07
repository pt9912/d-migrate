package dev.dmigrate.cli.commands

import dev.dmigrate.format.parquet.ParquetSingleFilePreflight
import dev.dmigrate.format.parquet.ResolvedParquetSingleFile
import dev.dmigrate.streaming.ImportInput

/**
 * Parquet Cut A S6 (AP11 §6.2 / AP12 §5): produktive
 * [ImportInputPhase2Hook]-Implementierung.
 *
 * - `ResolvedSingleFile` → ruft [ParquetSingleFilePreflight.phase2]
 *   mit einem aus dem Port-DTO rekonstruierten
 *   [ResolvedParquetSingleFile]. Heute (S6) reicht der Runner
 *   `resumeExpectedSha256 = null`, womit `phase2` ein Pass-Through
 *   ist. Sobald S8 den Resume-Hash aus
 *   `SingleFileCheckpointSpecifics` durchreicht, validiert dieselbe
 *   Methode den Content-Hash.
 * - `ResolvedBundle`: Pass-Through (Bundle-Phase-1 ist via
 *   `ParquetBundleResolver` schon final, AP9 §4.3).
 * - Alles andere (Stdin, JSON/YAML/CSV-Pfade): Identity.
 *
 * Das `manifestPresent`-Flag wird beim Rekonstruieren auf `true`
 * gesetzt, weil [ParquetSingleFilePreflight.phase2] das Feld
 * heute nicht liest. Sollte sich das aendern, muss S8 die
 * Phase-1-Information durch den Hook tragen (z.B. via Side-Channel
 * auf [ImportInput.ResolvedSingleFile]).
 */
class ParquetImportInputPhase2Hook(
    private val preflight: ParquetSingleFilePreflight = ParquetSingleFilePreflight(),
) : ImportInputPhase2Hook {

    override fun finalize(
        input: ImportInput,
        resumeExpectedSha256: String?,
    ): ImportInput {
        if (input !is ImportInput.ResolvedSingleFile) return input
        val phase1 = ResolvedParquetSingleFile(
            path = input.path,
            table = input.table,
            schema = input.schema,
            contentSha256 = input.contentSha256,
            manifestPresent = true,
        )
        preflight.phase2(phase1, resumeExpectedSha256)
        return input
    }
}
