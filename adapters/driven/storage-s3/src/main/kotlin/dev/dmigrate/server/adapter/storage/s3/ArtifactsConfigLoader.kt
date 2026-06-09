package dev.dmigrate.server.adapter.storage.s3

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Harte Konfig-Fehler in der `artifacts`-Sektion der `.d-migrate.yaml`. */
class ArtifactsConfigException(message: String) : RuntimeException(message)

/**
 * Liest die optionale `artifacts`-Sektion der `.d-migrate.yaml`
 * (ImpPlan-0.9.8-object-storage-s3, S3.4a):
 *
 * ```yaml
 * artifacts:
 *   store: s3            # file (Default) | s3
 *   s3:
 *     endpoint: "..."    # optional; fehlt = echtes AWS
 *     bucket: "..."      # Pflicht bei store: s3
 *     region: "..."      # optional, Default us-east-1
 *     prefix: "..."      # optional
 *     pathStyle: true    # optional, Default true (S3-kompatibel)
 * ```
 *
 * **Keine Credentials im YAML** — die kommen aus der
 * `DefaultCredentialsProviderChain` (env). Fehlende Datei/Sektion →
 * [ArtifactStorageConfig.File] (rueckwaertskompatibel). Unbekannter
 * `store`-Wert, `store: s3` ohne `artifacts.s3`-Block oder ohne `bucket` →
 * [ArtifactsConfigException]. Dieselbe snakeyaml-Toolchain wie
 * `YamlConnectionReferenceLoader`.
 */
object ArtifactsConfigLoader {

    private const val DEFAULT_REGION = "us-east-1"

    fun load(configPath: Path?): ArtifactStorageConfig {
        val artifacts = configPath?.takeIf { Files.exists(it) }
            ?.let { parseYaml(it) }
            ?.let { it["artifacts"] as? Map<*, *> }
            ?: return ArtifactStorageConfig.File
        return when (val store = (artifacts["store"] as? String)?.trim()?.lowercase() ?: "file") {
            "file" -> ArtifactStorageConfig.File
            "s3" -> ArtifactStorageConfig.S3(parseS3(artifacts["s3"] as? Map<*, *>))
            else -> throw ArtifactsConfigException("artifacts.store must be 'file' or 's3', was '$store'")
        }
    }

    private fun parseS3(s3: Map<*, *>?): S3StorageConfig {
        if (s3 == null) {
            throw ArtifactsConfigException("artifacts.store=s3 requires an 'artifacts.s3' block")
        }
        val bucket = (s3["bucket"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ArtifactsConfigException("artifacts.s3.bucket is required when store=s3")
        return S3StorageConfig(
            bucket = bucket,
            region = (s3["region"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REGION,
            endpoint = (s3["endpoint"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { URI.create(it) },
            keyPrefix = (s3["prefix"] as? String)?.trim() ?: "",
            pathStyle = s3["pathStyle"] as? Boolean ?: true,
        )
    }

    private fun parseYaml(path: Path): Map<*, *> {
        val settings = LoadSettings.builder().build()
        val loaded = Files.newInputStream(path).use { Load(settings).loadFromInputStream(it) }
        return loaded as? Map<*, *> ?: emptyMap<Any?, Any?>()
    }
}
