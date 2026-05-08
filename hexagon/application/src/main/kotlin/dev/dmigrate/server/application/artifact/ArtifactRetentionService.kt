package dev.dmigrate.server.application.artifact

import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import java.time.Instant

/**
 * Deletes expired artifact metadata together with immutable content bytes
 * and releases the LF-010 / LF-013 / LN-009 / LN-011 `STORED_ARTIFACT_BYTES` quota.
 */
class ArtifactRetentionService(
    private val artifactStore: ArtifactStore,
    private val contentStore: ArtifactContentStore,
    private val quotaService: QuotaService? = null,
) {

    fun deleteExpired(now: Instant): Int {
        val deleted = artifactStore.deleteExpiredRecords(now)
        for (record in deleted) {
            contentStore.delete(record.managedArtifact.artifactId)
            quotaService?.release(
                QuotaReservation(
                    key = QuotaKey(
                        tenantId = record.tenantId,
                        dimension = QuotaDimension.STORED_ARTIFACT_BYTES,
                        principalId = record.ownerPrincipalId,
                    ),
                    amount = record.managedArtifact.sizeBytes,
                ),
            )
        }
        return deleted.size
    }
}
