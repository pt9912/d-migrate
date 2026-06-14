package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput

/**
 * Konsolidierter Hook-Port fuer Input-Aufloesung mit zwei
 * Call-Sites entlang der Pipeline:
 *
 * - [resolveBeforeSchema]: `ImportPreflightResolver` zwischen
 *   `resolveImportInput` und `resolveSchemaPreflight`. Darf
 *   `ImportInput.SingleFile`/`Directory` in `ResolvedSingleFile`/
 *   `ResolvedBundle` transformieren — Schema-Preflight sieht dann
 *   den finalisierten Input.
 * - [finalizeBeforePrepare]: `DataImportRunner.runImport` direkt
 *   vor `ImportExecutionPlanner.prepare`. Heute mit
 *   `resumeExpectedSha256 = null` ein Pass-Through; S8 plumbt den
 *   echten Resume-Hash aus `SingleFileCheckpointSpecifics` durch.
 *
 * Modulgrenze: Implementierungen leben in den Adaptern;
 * `:hexagon:application` haelt nur den Port + [NoOp]-Default.
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
