package dev.dmigrate.cli.commands

import dev.dmigrate.format.parquet.preflight.ParquetSingleFileResolver
import dev.dmigrate.streaming.ImportInput

/**
 * Parquet Cut A S6 (AP11 §6.2 / AP12 §5): produktive
 * [ImportInputPhase2Hook]-Implementierung.
 *
 * - `ResolvedSingleFile` → ruft [ParquetSingleFileResolver.phase2], der
 *   den Adapter wieder verlustfrei umkehrt und ggf. eine angereicherte
 *   `ImportInput.ResolvedSingleFile` zurueckliefert. `manifestPresent`,
 *   Schema und Content-Hash wandern hin und zurueck (Review-Findings
 *   B2/B3/C2/D4). Heute (S6) reicht der Runner `resumeExpectedSha256
 *   = null`, womit der Resolver einen schnellen Pass-Through faehrt.
 *   Sobald S8 den Resume-Hash aus `SingleFileCheckpointSpecifics`
 *   durchreicht, validiert derselbe Pfad den Content-Hash UND uebernimmt
 *   einen ggf. von `phase2` zurueckgegebenen schema-Fixup verlustfrei.
 * - `ResolvedBundle`: Pass-Through (Bundle-Phase-1 ist via
 *   `ParquetBundleResolver` schon final, AP9 §4.3).
 * - Alles andere (Stdin, JSON/YAML/CSV-Pfade): Identity.
 */
class ParquetImportInputPhase2Hook(
    private val singleFileResolver: ParquetSingleFileResolver = ParquetSingleFileResolver(),
) : ImportInputPhase2Hook {

    override fun finalize(
        input: ImportInput,
        resumeExpectedSha256: String?,
    ): ImportInput {
        if (input !is ImportInput.ResolvedSingleFile) return input
        return singleFileResolver.phase2(
            input = input,
            resumeExpectedSha256 = resumeExpectedSha256,
        )
    }
}
