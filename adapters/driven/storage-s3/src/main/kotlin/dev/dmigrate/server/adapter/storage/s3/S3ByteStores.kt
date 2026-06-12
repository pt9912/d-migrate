package dev.dmigrate.server.adapter.storage.s3

import software.amazon.awssdk.services.s3.S3Client

/**
 * Buendelt die beiden S3-Byte-Stores auf einem geteilten, gate-validierten
 * [S3ClientFactory]-Client (ImpPlan-0.9.8-object-storage-s3, S3.4b).
 *
 * Existiert, damit Konsumenten (CLI-MCP-Wiring) die Stores aus einer
 * [S3StorageConfig] bauen koennen, ohne den AWS-SDK-Typ `S3Client` zu
 * beruehren — das SDK bleibt `implementation`-Detail dieses Moduls und
 * landet nicht auf dem Compile-Classpath der Konsumenten.
 *
 * Das Buendel besitzt den Client: [close] gibt ihn frei. Der Server-
 * Lifecycle schliesst das Buendel ueber `McpRuntimeWiring.ownedResources`.
 */
class S3ByteStores private constructor(
    private val client: S3Client,
    val uploadSegmentStore: S3UploadSegmentStore,
    val artifactContentStore: S3ArtifactContentStore,
) : AutoCloseable {

    override fun close() {
        client.close()
    }

    companion object {
        fun create(config: S3StorageConfig): S3ByteStores {
            val client = S3ClientFactory.create(config)
            return S3ByteStores(
                client = client,
                uploadSegmentStore = S3UploadSegmentStore(client, config.bucket, config.keyPrefix),
                artifactContentStore = S3ArtifactContentStore(client, config.bucket, config.keyPrefix),
            )
        }
    }
}
