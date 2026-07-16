package dev.dmigrate.cli.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * LN-005 (R2): parst `export.parquet.row_group_bytes` aus der effektiven
 * `.d-migrate.yaml` (gemeinsamer [loadEffectiveConfig]-Loader). Steuert die
 * Parquet-Row-Group-Größe des `data export`-Pfads (Heap-Budget beim parallelen
 * Parquet-Export); ohne Config-Wert greift der eingebaute Default
 * (32 MiB, `ParquetChunkWriter.DEFAULT_ROW_GROUP_BYTES`).
 *
 * Ein Format-/Export-Detail liegt unter `export.parquet.*`, nicht unter `pipeline.*`.
 * Ein gesetzter, aber nicht-positiver/nicht-ganzzahliger Wert → [ConfigResolveException].
 */
class ParquetExportConfigResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** `export.parquet.row_group_bytes` (positive Ganzzahl) oder `null`, wenn nicht gesetzt. */
    fun resolveRowGroupBytes(): Long? {
        val (root, path) = loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val export = root?.get("export") as? Map<*, *> ?: return null
        val parquet = export["parquet"] as? Map<*, *> ?: return null
        if (!parquet.containsKey("row_group_bytes")) return null
        return requirePositiveLongConfig(parquet["row_group_bytes"], "export.parquet.row_group_bytes", path)
    }
}
