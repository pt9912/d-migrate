package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput

/**
 * AP12 §5 / Parquet Cut A Umbrella §3 S6 / Review-Finding F3:
 * konsolidierter Hook-Port fuer die Input-Aufloesung. Loest die in
 * S6-iii eingefuehrten getrennten `ImportInputPhase1Hook` und
 * `ImportInputPhase2Hook` zusammen, weil eine Adapter-Implementierung
 * (heute: Parquet) ohnehin in beiden Stellen tritt.
 *
 * Die zwei Methoden bleiben getrennt, weil sie an unterschiedlichen
 * Stellen der Pipeline laufen muessen:
 *
 * - [resolveBeforeSchema] ruft der `ImportPreflightResolver` zwischen
 *   `resolveImportInput` und `resolveSchemaPreflight`. Der Hook darf
 *   `ImportInput.SingleFile`/`Directory` in `ResolvedSingleFile`/
 *   `ResolvedBundle` transformieren, damit das Schema-Preflight bereits
 *   den finalisierten Input sieht (z.B. `tableOrder`-Sortierung des
 *   `ResolvedBundle`, Review-Finding A1).
 * - [finalizeBeforePrepare] ruft der `DataImportRunner.runImport` direkt
 *   vor `ImportExecutionPlanner.prepare`. Heute (S6) reicht der Runner
 *   `resumeExpectedSha256 = null`; sobald S8 den Resume-Hash aus
 *   `SingleFileCheckpointSpecifics` durchreicht, validiert der Hook den
 *   Content-Hash UND uebernimmt einen ggf. von Phase-2 zurueckgegebenen
 *   Schema-Fix-up verlustfrei (B3).
 *
 * Modulgrenze: Implementierungen liegen in den jeweiligen Adaptern;
 * `:hexagon:application` haelt nur den Port + den Identity-Default
 * ([ImportInputResolutionHook.NoOp]).
 */
interface ImportInputResolutionHook {

    fun resolveBeforeSchema(
        rawInput: ImportInput,
        format: DataExportFormat,
        computeContentSha256: Boolean,
    ): ImportInput

    fun finalizeBeforePrepare(
        input: ImportInput,
        resumeExpectedSha256: String?,
    ): ImportInput

    companion object {
        /**
         * Default-Singleton: liefert beide Methoden als Identity.
         * Repo-Konvention: `NoOp*`-Stil parallel zu `NoOpProgressReporter`
         * (Review-Finding C3, soft-applied).
         */
        val NoOp: ImportInputResolutionHook = object : ImportInputResolutionHook {
            override fun resolveBeforeSchema(
                rawInput: ImportInput,
                format: DataExportFormat,
                computeContentSha256: Boolean,
            ): ImportInput = rawInput

            override fun finalizeBeforePrepare(
                input: ImportInput,
                resumeExpectedSha256: String?,
            ): ImportInput = input
        }
    }
}
