package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.parquet.ParquetSingleFilePreflight
import dev.dmigrate.format.parquet.preflight.ParquetBundleResolver
import dev.dmigrate.streaming.ImportInput

/**
 * Parquet Cut A S6 (AP12 §5.1 / AP9, AP11 §6.2): produktive
 * [ImportInputPhase1Hook]-Implementierung.
 *
 * - `Directory + PARQUET` → [ParquetBundleResolver.resolve] → `ResolvedBundle`.
 * - `SingleFile + PARQUET` → [ParquetSingleFilePreflight.phase1] → `ResolvedSingleFile`
 *   (Hash optional, abhaengig von `computeContentSha256`).
 * - Alles andere (Stdin, JSON/YAML/CSV, bereits aufgeloeste Varianten):
 *   Identity.
 *
 * Modulgrenze: Diese Klasse lebt im CLI-Modul, weil sie als einzige
 * Stelle Parquet-Adapter-Klassen sieht. `:hexagon:application` haelt
 * nur die Port-`fun interface`.
 */
class ParquetImportInputPhase1Hook(
    private val bundleResolver: ParquetBundleResolver = ParquetBundleResolver(),
    private val singleFilePreflight: ParquetSingleFilePreflight = ParquetSingleFilePreflight(),
) : ImportInputPhase1Hook {

    override fun maybeFinalize(
        rawInput: ImportInput,
        format: DataExportFormat,
        computeContentSha256: Boolean,
    ): ImportInput {
        if (format != DataExportFormat.PARQUET) return rawInput
        return when (rawInput) {
            is ImportInput.Directory -> bundleResolver.resolve(
                bundleRoot = rawInput.path,
                tableFilter = rawInput.tableFilter,
                tableOrder = rawInput.tableOrder,
            )
            is ImportInput.SingleFile -> {
                val phase1 = singleFilePreflight.phase1(
                    path = rawInput.path,
                    explicitTable = rawInput.table,
                    computeContentSha256 = computeContentSha256,
                )
                ImportInput.ResolvedSingleFile(
                    table = phase1.table,
                    path = phase1.path,
                    schema = phase1.schema,
                    contentSha256 = phase1.contentSha256,
                )
            }
            // Stdin (von validateFormatPathRequirements bereits abgelehnt),
            // ResolvedBundle/ResolvedSingleFile (kommen aus diesem Hook
            // nicht doppelt durch) — Identity.
            is ImportInput.Stdin,
            is ImportInput.ResolvedBundle,
            is ImportInput.ResolvedSingleFile -> rawInput
        }
    }
}
