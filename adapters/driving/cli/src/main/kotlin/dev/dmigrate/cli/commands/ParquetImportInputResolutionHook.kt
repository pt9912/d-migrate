package dev.dmigrate.cli.commands

import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.parquet.preflight.ParquetBundlePreflightException
import dev.dmigrate.format.parquet.preflight.ParquetBundleResolver
import dev.dmigrate.format.parquet.preflight.ParquetSingleFileResolver
import dev.dmigrate.streaming.ImportInput

/**
 * Konsolidierter Parquet-Hook (Review-Finding F3). Vereint die in S6-iii
 * separat eingefuehrten ParquetImportInputPhase1Hook und
 * ParquetImportInputPhase2Hook.
 *
 * Aufrufkontexte (vom Runner gesteuert):
 *
 * - [resolveBeforeSchema] (AP11 §5.5 / AP12 §5.1 / AP9):
 *   * `Directory + PARQUET` → [ParquetBundleResolver.resolve] →
 *     `ResolvedBundle` (verifyContentSha256 = computeContentSha256).
 *   * `SingleFile + PARQUET` → [ParquetSingleFileResolver.phase1] →
 *     `ResolvedSingleFile` (manifestPresent + contentSha256 verlustfrei
 *     ueber den Adapter).
 *   * Stdin+PARQUET → expliziter Throw (Defense-in-Depth, I1).
 *   * Bereits aufgeloeste Sealed-Varianten → idempotenter Pass-Through
 *     mit check(format == PARQUET) (I2).
 *   * Nicht-Parquet-Formate → Pass-Through.
 *
 * - [finalizeBeforePrepare] (AP11 §6.2 / AP12 §5):
 *   * `ResolvedSingleFile` → [ParquetSingleFileResolver.phase2]:
 *     Pass-Through bei `resumeExpectedSha256 == null`. Der Runner reicht
 *     hier produktiv `null` (S8d-Re-Cut 2026-06-09) — der Cross-Run-
 *     Resume-Hash-Gate lebt in `ImportCheckpointManager.validateSingleFileResume`
 *     (S8c), nicht in diesem Hook. Ein non-null-Phase-2-Hash-Check
 *     braeuchte den im Resume-Manifest persistierten Hash und damit einen
 *     Orchestrierungs-Reorder (eigener Folge-Slice). Die Hook-Mechanik
 *     bleibt erhalten, damit dieser Folge-Slice ohne Strukturumbau am Hook
 *     umgesetzt werden kann.
 *   * Alles andere → Pass-Through.
 *
 * Modulgrenze: lebt im CLI-Modul, weil hier beide Parquet-Adapter
 * sichtbar sind. `:hexagon:application` haelt nur den Port-Vertrag.
 */
class ParquetImportInputResolutionHook(
    private val bundleResolver: ParquetBundleResolver = ParquetBundleResolver(),
    private val singleFileResolver: ParquetSingleFileResolver = ParquetSingleFileResolver(),
) : ImportInputResolutionHook {

    override fun resolveBeforeSchema(
        rawInput: ImportInput,
        format: DataExportFormat,
        computeContentSha256: Boolean,
    ): ImportInput {
        if (format != DataExportFormat.PARQUET) return rawInput
        return when (rawInput) {
            is ImportInput.Directory -> try {
                bundleResolver.resolve(
                    bundleRoot = rawInput.path,
                    tableFilter = rawInput.tableFilter,
                    tableOrder = rawInput.tableOrder,
                    verifyContentSha256 = computeContentSha256,
                )
            } catch (e: ParquetBundlePreflightException) {
                // S9a-0.b (AP12 §9): MANIFEST_* → Exit 4. Modulgrenze:
                // ParquetBundlePreflightException ist dem :hexagon:application-
                // Core unsichtbar; der Hook uebersetzt sie hier in das exit-
                // code-tragende PreflightExitException, das der Resolver auf
                // Exit 4 mappt. (Die Bundle-Resolver-Familie BUNDLE_* → Exit 5
                // kommt mit S9a-0.c als eigene Exception.)
                throw PreflightExitException(
                    exitCode = 4,
                    message = e.message ?: "MANIFEST_ERROR: parquet bundle preflight failed",
                    cause = e,
                )
            }
            is ImportInput.SingleFile -> {
                val explicitTable = rawInput.table
                    .takeUnless { it == UNRESOLVED_PARQUET_TABLE_SENTINEL }
                singleFileResolver.phase1(
                    path = rawInput.path,
                    explicitTable = explicitTable,
                    computeContentSha256 = computeContentSha256,
                )
            }
            is ImportInput.Stdin -> error(
                "PARQUET_STDIN_NOT_SUPPORTED: Parquet single-file imports require " +
                    "a file path, not stdin."
            )
            is ImportInput.ResolvedBundle,
            is ImportInput.ResolvedSingleFile -> {
                check(format == DataExportFormat.PARQUET) {
                    "ParquetImportInputResolutionHook reached with non-Parquet format=$format " +
                        "on already-resolved input — wiring error."
                }
                rawInput
            }
        }
    }

    override fun finalizeBeforePrepare(
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
