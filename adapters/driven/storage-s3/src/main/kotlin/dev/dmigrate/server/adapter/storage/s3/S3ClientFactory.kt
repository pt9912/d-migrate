package dev.dmigrate.server.adapter.storage.s3

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration

/**
 * Baut den `S3Client` nach der S3.0-gate-validierten Konfiguration
 * (ImpPlan-0.9.8-object-storage-s3, S3-Client-Konfiguration):
 *  - `url-connection-client` (sync; kein Netty/Apache — Footprint, object-storage-s3-eval.md),
 *  - `requestChecksumCalculation`/`responseChecksumValidation = WHEN_REQUIRED`
 *    — **Pflicht**: AWS SDK v2 (>= 2.30) rechnet sonst per Default
 *    Integritaets-Checksums (aws-chunked), die SeaweedFS mit
 *    `Content-Md5 not valid` (400) ablehnt (S3.0-Befund 2026-06-09),
 *  - Path-Style + optionaler `endpointOverride` fuer S3-kompatible Ziele.
 *  - erhoehte Retry-Resilienz ([MAX_RETRY_ATTEMPTS]): S3-kompatible Stores
 *    (SeaweedFS unter Last) emittieren transiente 5xx („internal error,
 *    please try again"); der Default (4 Versuche) ueberbrueckt CI-Last-
 *    Hiccups nicht zuverlaessig. Standard-Backoff bleibt (exponentiell).
 *
 * Ohne statische Credentials greift die `DefaultCredentialsProviderChain`.
 */
object S3ClientFactory {

    private const val MAX_RETRY_ATTEMPTS = 8

    fun create(config: S3StorageConfig): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(config.region))
            .serviceConfiguration(
                S3Configuration.builder().pathStyleAccessEnabled(config.pathStyle).build(),
            )
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    .retryStrategy(
                        AwsRetryStrategy.standardRetryStrategy().toBuilder()
                            .maxAttempts(MAX_RETRY_ATTEMPTS)
                            .build(),
                    )
                    .build(),
            )
            .httpClient(UrlConnectionHttpClient.create())

        config.endpoint?.let { builder.endpointOverride(it) }

        if (config.accessKey != null && config.secretKey != null) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKey, config.secretKey),
                ),
            )
        }
        return builder.build()
    }
}
