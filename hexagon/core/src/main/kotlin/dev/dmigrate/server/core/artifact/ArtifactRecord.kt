package dev.dmigrate.server.core.artifact

import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

enum class ArtifactKind {
    SCHEMA,
    PROFILE,
    DIFF,
    DATA_EXPORT,
    UPLOAD_INPUT,
    OTHER,
}

data class ArtifactUploadMetadata(
    val artifactId: String,
    val resourceUri: String,
    val uploadIntent: String,
    val wireArtifactKind: String,
    val contentType: String,
    val format: String? = null,
    val targetTable: String? = null,
    val targetTables: List<String>? = null,
    val sourceUploadSessionId: String,
    val policyFingerprint: String? = null,
    val sizeBytes: Long,
    val sha256: String,
    /**
     * Follow-up AP 2: Bundle-/Mehrtabellen-Import. Wenn das Artefakt
     * ein versioniertes Seed-Bundle ist (z. B. `seed-bundle.v1.zip`),
     * trägt diese Spalte die Bundle-Format-Identität. Persistente
     * Bundle-Hints sichern, dass `data_import_start.tables` gegen den
     * gleichen Manifest-Stand validiert wird, mit dem die
     * `artifact_upload_init`-Session den Upload eingeleitet hat.
     * `null` für Single-File-Uploads (AP-2-vor-Bundle-Pfad).
     */
    val bundleFormat: String? = null,
    /**
     * Follow-up AP 2: Pfad der Manifest-Datei innerhalb des Bundles
     * (LF-010 / LF-013 / LN-009 / LN-011 "Manifest-Datei im Bundle ist Pflicht"). Pfadnormalisiert
     * (`/`-Separator, kein führender `/`, kein `..`). `null` für
     * Single-File-Artefakte.
     */
    val manifestPath: String? = null,
    /**
     * Follow-up AP 2: SHA-256 der Manifest-Bytes. Der Import-Handler
     * vergleicht diesen Wert gegen den frisch berechneten Fingerprint,
     * den der Runner beim Extrahieren ermittelt — driftet das
     * Manifest, fällt der Job-Start mit `IDEMPOTENCY_CONFLICT`
     * (gleicher `idempotencyKey`, abweichender Manifest-Fingerprint).
     * `null` für Single-File-Artefakte.
     */
    val manifestFingerprint: String? = null,
)

data class ArtifactRecord(
    val managedArtifact: ManagedArtifact,
    val kind: ArtifactKind,
    val tenantId: TenantId,
    val ownerPrincipalId: PrincipalId,
    val visibility: JobVisibility,
    val resourceUri: ServerResourceUri,
    val adminScope: String? = null,
    val jobRef: String? = null,
    val uploadMetadata: ArtifactUploadMetadata? = null,
) {
    fun isReadableBy(
        principal: PrincipalContext,
        addressedTenantId: TenantId = principal.effectiveTenantId,
    ): Boolean {
        if (tenantId != addressedTenantId) return false
        return when (visibility) {
            JobVisibility.OWNER -> principal.principalId == ownerPrincipalId
            JobVisibility.TENANT -> true
            JobVisibility.ADMIN -> principal.isAdmin
        }
    }
}
