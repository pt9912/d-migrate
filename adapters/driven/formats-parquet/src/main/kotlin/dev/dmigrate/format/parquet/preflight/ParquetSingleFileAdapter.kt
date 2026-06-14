package dev.dmigrate.format.parquet.preflight

import dev.dmigrate.format.parquet.ParquetSingleFilePreflight
import dev.dmigrate.format.parquet.ResolvedParquetSingleFile
import dev.dmigrate.streaming.ImportInput
import java.nio.file.Path

/**
 * AP11 §6.2 / AP12 §5.1 (Review-Finding C2): einzige Stelle, an der das
 * adapter-interne [ResolvedParquetSingleFile] in das port-eigene
 * [ImportInput.ResolvedSingleFile] uebersetzt wird — und zurueck.
 *
 * Symmetrisch zu [ParquetBundleAdapter] (Bundle-Pfad). Bisher (S6) baute
 * jeder Hook die Felder von Hand zusammen und faengte sich dabei einen
 * hartkodierten `manifestPresent = true` (Review-Finding B2) und einen
 * verworfenen `phase2`-Returnwert (Review-Finding B3) ein. Der Adapter
 * macht beide Richtungen verlustfrei.
 */
internal object ParquetSingleFileAdapter {

    fun toResolvedSingleFile(phase: ResolvedParquetSingleFile): ImportInput.ResolvedSingleFile =
        ImportInput.ResolvedSingleFile(
            table = phase.table,
            path = phase.path,
            schema = phase.schema,
            contentSha256 = phase.contentSha256,
            manifestPresent = phase.manifestPresent,
        )

    fun toResolvedParquetSingleFile(input: ImportInput.ResolvedSingleFile): ResolvedParquetSingleFile =
        ResolvedParquetSingleFile(
            path = input.path,
            table = input.table,
            schema = input.schema,
            contentSha256 = input.contentSha256,
            manifestPresent = input.manifestPresent,
        )
}

/**
 * Symmetrische Public-Entry zu [ParquetBundleResolver]: kapselt
 * `ParquetSingleFilePreflight.phase1`/`phase2` + den Adapter, sodass
 * die CLI-Hooks rein gegen Port-DTOs (`ImportInput.ResolvedSingleFile`)
 * arbeiten und manifestPresent/Schema/Hash verlustfrei rundlaufen.
 */
class ParquetSingleFileResolver(
    private val preflight: ParquetSingleFilePreflight = ParquetSingleFilePreflight(),
) {

    fun phase1(
        path: Path,
        explicitTable: String?,
        computeContentSha256: Boolean,
    ): ImportInput.ResolvedSingleFile {
        val phase1 = preflight.phase1(
            path = path,
            explicitTable = explicitTable,
            computeContentSha256 = computeContentSha256,
        )
        return ParquetSingleFileAdapter.toResolvedSingleFile(phase1)
    }

    fun phase2(
        input: ImportInput.ResolvedSingleFile,
        resumeExpectedSha256: String?,
    ): ImportInput.ResolvedSingleFile {
        // Review-Finding D4: wenn der Resume-Hash nicht uebergeben wird,
        // ist `phase2` heute (S6) ein Pass-Through. Vermeiden des
        // Round-Trips spart die Allokation des adapter-internen DTO.
        if (resumeExpectedSha256 == null) return input
        val phase1 = ParquetSingleFileAdapter.toResolvedParquetSingleFile(input)
        val phase2Result = preflight.phase2(phase1, resumeExpectedSha256)
        return ParquetSingleFileAdapter.toResolvedSingleFile(phase2Result)
    }
}
