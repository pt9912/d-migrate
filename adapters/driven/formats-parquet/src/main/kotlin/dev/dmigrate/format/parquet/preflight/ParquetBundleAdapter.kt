package dev.dmigrate.format.parquet.preflight

import dev.dmigrate.streaming.BundleResumeFingerprint
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ResolvedBundleTableBinding

/**
 * AP9 §4.3: einzige Stelle, an der das adapter-interne
 * [ResolvedParquetBundle] in das port-eigene
 * [ImportInput.ResolvedBundle] uebersetzt wird.
 * Manifest-spezifische Begriffe (`schemaSource`,
 * `tables[].columns[].neutralType`, ...) sind ab hier nicht
 * mehr sichtbar.
 *
 * Bewusst nur EINE Eingabequelle: der `ParquetBundlePreflight` traegt
 * Manifest-Header-Felder und Bindings in derselben
 * [ResolvedParquetBundle]-Instanz; ein extern uebergebenes Manifest
 * koennte gegenueber dem im Preflight gehaltenen divergieren.
 */
internal object ParquetBundleAdapter {

    fun toResolvedBundle(bundle: ResolvedParquetBundle): ImportInput.ResolvedBundle {
        val bindings = bundle.tables.map { binding ->
            ResolvedBundleTableBinding(
                table = binding.table,
                path = binding.path,
                schema = binding.schema,
                expectedSha256 = binding.expectedSha256,
            )
        }
        val fingerprint = BundleResumeFingerprint(
            manifestSha256 = bundle.manifestSha256,
            formatVersion = bundle.formatVersion,
            producerVersion = bundle.producerVersion,
            tableOrder = bindings.map { it.table },
        )
        return ImportInput.ResolvedBundle(
            bundleRoot = bundle.bundleRoot,
            tables = bindings,
            resumeFingerprint = fingerprint,
        )
    }
}

/**
 * S5a CLI-Einstiegspunkt fuer den Bundle-Pfad: ruft den
 * [ParquetBundlePreflight] und uebersetzt direkt zum Port-DTO.
 * Wirft [ParquetBundlePreflightException] bei Validierungsfehlern.
 */
class ParquetBundleResolver(
    private val preflight: ParquetBundlePreflight = ParquetBundlePreflight(),
) {

    fun resolve(
        bundleRoot: java.nio.file.Path,
        tableFilter: List<String>? = null,
        tableOrder: List<String>? = null,
    ): ImportInput.ResolvedBundle {
        val bundle = preflight.run(bundleRoot, tableFilter, tableOrder)
        return ParquetBundleAdapter.toResolvedBundle(bundle)
    }
}
