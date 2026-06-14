package dev.dmigrate.server.adapter.storage.s3

/**
 * Auswahl des Byte-Store-Backends (ImpPlan-0.9.8-object-storage-s3, S3.4a).
 * Aus der `artifacts.store`-Sektion der `.d-migrate.yaml` via
 * [ArtifactsConfigLoader] geladen; vom CLI-MCP-Wiring (S3.4b) konsumiert.
 */
sealed interface ArtifactStorageConfig {

    /** Der `artifacts.store`-YAML-Wert der Variante — fuer Diagnose-Meldungen. */
    val storeId: String

    /** Dateibasierte Byte-Stores (Default, Bestandsverhalten). */
    data object File : ArtifactStorageConfig {
        override val storeId: String = "file"
    }

    /** S3-kompatible Byte-Stores mit der gegebenen [config]. */
    data class S3(val config: S3StorageConfig) : ArtifactStorageConfig {
        override val storeId: String = "s3"
    }
}
