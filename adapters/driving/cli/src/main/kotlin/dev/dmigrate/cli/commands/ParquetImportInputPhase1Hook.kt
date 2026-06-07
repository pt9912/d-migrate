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
                // AP12 §4.2: --no-checkpoint deaktiviert die Per-File-Hash-
                // Verifikation des Bundle-Preflights symmetrisch zum
                // SingleFile-Pfad (Review-Finding A5).
                verifyContentSha256 = computeContentSha256,
            )
            is ImportInput.SingleFile -> {
                // AP11 §5.5: `--table` ist Override; ohne `--table` setzt
                // resolveImportInput den Sentinel, sodass phase1 den
                // Tabellennamen aus dem Footer-KV `d-migrate.manifest`
                // ableitet.
                val explicitTable = rawInput.table
                    .takeUnless { it == UNRESOLVED_PARQUET_TABLE_SENTINEL }
                val phase1 = singleFilePreflight.phase1(
                    path = rawInput.path,
                    explicitTable = explicitTable,
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
