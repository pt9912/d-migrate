package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.ImportInput

/**
 * AP12 §5.1 / Parquet Cut A Umbrella §3 S6: parquet-freier Hook,
 * den der [ImportPreflightResolver] nach der Roh-`ImportInput`-
 * Aufloesung (Stdin/SingleFile/Directory) ruft. Eine produktive
 * Implementierung kann die Roh-Variante in
 * [ImportInput.ResolvedBundle]/[ImportInput.ResolvedSingleFile]
 * transformieren — der CLI verdrahtet hier
 * `ParquetBundleResolver` bzw. `ParquetSingleFilePreflight.phase1`.
 *
 * Modulgrenze: Implementierungen liegen in den jeweiligen Adaptern;
 * `:hexagon:application` haelt nur den Port + die Identity-Default-
 * Variante ([ImportInputPhase1Hook.IDENTITY]).
 */
fun interface ImportInputPhase1Hook {

    /**
     * @param rawInput Vom CLI/Helper aufgeloester Roh-`ImportInput`.
     * @param format Vom Resolver bestimmtes Zielformat.
     * @param computeContentSha256 Wenn `true`, soll die Implementierung
     *   den Inhalts-Hash sofort berechnen (Resume aktiv). Wird vom
     *   CLI-Wiring aus `!request.noCheckpoint` abgeleitet (S6 (v)).
     * @return Roh-`ImportInput` unveraendert oder eine bereits
     *   aufgeloeste Sealed-Variante.
     */
    fun maybeFinalize(
        rawInput: ImportInput,
        format: DataExportFormat,
        computeContentSha256: Boolean,
    ): ImportInput

    companion object {
        /** Identity-Default: liefert den Roh-`ImportInput` unveraendert. */
        val IDENTITY: ImportInputPhase1Hook = ImportInputPhase1Hook { raw, _, _ -> raw }
    }
}

/**
 * AP12 §5 / Parquet Cut A Umbrella §3 S6: parquet-freier Hook,
 * den der [DataImportRunner] **vor** [ImportExecutionPlanner.prepare]
 * ruft, damit Fingerprint, Resume-Context und Initialmanifest
 * gegen den finalen Input rechnen. CLI verdrahtet hier
 * `ParquetSingleFilePreflight.phase2` (Hash-Konsistenz fuer Resume,
 * AP11 §6.4).
 *
 * Implementierungen duerfen den uebergebenen [ImportInput]
 * unveraendert zurueckgeben oder eine finalisierte Sealed-Variante.
 * Bei Validierungsfehlern werfen sie eine Domain-Exception, die der
 * Runner auf Exit 3 abbildet.
 *
 * In S6 reicht der Runner immer `resumeExpectedSha256 = null` — der
 * non-null-Pfad kommt mit `SingleFileCheckpointSpecifics` in S8.
 */
fun interface ImportInputPhase2Hook {

    fun finalize(
        input: ImportInput,
        resumeExpectedSha256: String?,
    ): ImportInput

    companion object {
        /** Identity-Default: liefert den `ImportInput` unveraendert. */
        val IDENTITY: ImportInputPhase2Hook = ImportInputPhase2Hook { input, _ -> input }
    }
}
