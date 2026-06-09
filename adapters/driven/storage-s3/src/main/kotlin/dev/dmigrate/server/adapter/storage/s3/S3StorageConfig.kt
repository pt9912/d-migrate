package dev.dmigrate.server.adapter.storage.s3

import java.net.URI

/**
 * Konfiguration des S3-Storage-Adapters (ImpPlan-0.9.8-object-storage-s3 S3.1;
 * `.d-migrate.yaml` `artifacts.s3.*`, Eval §6).
 *
 * [accessKey]/[secretKey] sind optional: fehlen sie, nutzt [S3ClientFactory]
 * die `DefaultCredentialsProviderChain` (env/profile/IAM). [toString] redigiert
 * die Credentials, damit sie nicht in Logs/Reports landen (0.9.1-Haertung).
 */
data class S3StorageConfig(
    val bucket: String,
    val region: String = "us-east-1",
    /** `null` = echtes AWS S3; sonst S3-kompatibler Endpunkt (MinIO/SeaweedFS/Ceph). */
    val endpoint: URI? = null,
    val keyPrefix: String = "",
    val pathStyle: Boolean = true,
    val accessKey: String? = null,
    val secretKey: String? = null,
) {
    override fun toString(): String =
        "S3StorageConfig(bucket=$bucket, region=$region, endpoint=$endpoint, " +
            "keyPrefix='$keyPrefix', pathStyle=$pathStyle, " +
            "accessKey=${accessKey?.let { "***" }}, secretKey=${secretKey?.let { "***" }})"
}
