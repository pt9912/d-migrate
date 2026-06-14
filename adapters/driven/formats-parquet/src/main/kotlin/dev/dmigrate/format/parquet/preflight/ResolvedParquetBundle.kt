package dev.dmigrate.format.parquet.preflight

import dev.dmigrate.format.data.ChunkSchema
import java.nio.file.Path

/**
 * AP7 §9.1 Schritt 9 + AP9 §4.4: adapter-internes Ergebnis-DTO des
 * [ParquetBundlePreflight]. Wird vom `ParquetBundleAdapter` (AP9 §4.3)
 * in das port-eigene `ImportInput.ResolvedBundle` uebersetzt;
 * Adapter-spezifische Felder ([manifestSha256], [schemaSource],
 * vollstaendige Spaltenmetadaten) leben hier, nicht im Port.
 */
internal data class ResolvedParquetBundle(
    val bundleRoot: Path,
    val manifestSha256: String,
    val formatVersion: String,
    val producerVersion: String,
    val schemaSource: String,
    val tables: List<ResolvedParquetTableBinding>,
)

/**
 * Per-Tabelle-Binding im [ResolvedParquetBundle]. [schema] kommt aus
 * der `manifest.yaml`-Spaltenmetadaten-Sektion (AP8 §6.2); [expectedSha256]
 * ist `null`, wenn der Producer keinen Hash gesetzt hat.
 */
internal data class ResolvedParquetTableBinding(
    val table: String,
    val path: Path,
    val schema: ChunkSchema,
    val expectedSha256: String?,
)
