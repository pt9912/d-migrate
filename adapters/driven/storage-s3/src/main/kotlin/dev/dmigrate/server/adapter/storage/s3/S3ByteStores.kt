package dev.dmigrate.server.adapter.storage.s3

/**
 * Buendelt die beiden S3-Byte-Stores auf einem geteilten, gate-validierten
 * [S3ClientFactory]-Client (ImpPlan-0.9.8-object-storage-s3, S3.4b).
 *
 * Existiert, damit Konsumenten (CLI-MCP-Wiring) die Stores aus einer
 * [S3StorageConfig] bauen koennen, ohne den AWS-SDK-Typ `S3Client` zu
 * beruehren — das SDK bleibt `implementation`-Detail dieses Moduls und
 * landet nicht auf dem Compile-Classpath der Konsumenten.
 */
data class S3ByteStores(
    val uploadSegmentStore: S3UploadSegmentStore,
    val artifactContentStore: S3ArtifactContentStore,
) {
    companion object {
        fun create(config: S3StorageConfig): S3ByteStores {
            val client = S3ClientFactory.create(config)
            return S3ByteStores(
                uploadSegmentStore = S3UploadSegmentStore(client, config.bucket, config.keyPrefix),
                artifactContentStore = S3ArtifactContentStore(client, config.bucket, config.keyPrefix),
            )
        }
    }
}
