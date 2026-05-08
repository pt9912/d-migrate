package dev.dmigrate.server.core.upload

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

enum class UploadSessionState(val terminal: Boolean) {
    ACTIVE(terminal = false),

    /**
     * AP 6.22: transient single-writer claim while a completing
     * `tools/call` runs assembly + parse + validate +
     * artefact/schema materialisation. NOT a successful terminal
     * state — only the exclusive side-effect lock for the in-flight
     * finalisation. Concurrent completing calls compete for this
     * claim via [UploadSession.finalizingClaimId] /
     * [UploadSession.finalizingLeaseExpiresAt]; the loser must not
     * start a new assembly.
     */
    FINALIZING(terminal = false),

    COMPLETED(terminal = true),
    ABORTED(terminal = true),
    EXPIRED(terminal = true),
}

data class UploadSession(
    val uploadSessionId: String,
    val tenantId: TenantId,
    val ownerPrincipalId: PrincipalId,
    val resourceUri: ServerResourceUri,
    val artifactKind: ArtifactKind,
    val mimeType: String,
    val sizeBytes: Long,
    val segmentTotal: Int,
    val checksumSha256: String,
    val uploadIntent: String,
    val state: UploadSessionState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val idleTimeoutAt: Instant,
    val absoluteLeaseExpiresAt: Instant,
    val bytesReceived: Long = 0,
    /**
     * AP 6.18: persisted finalisation outcome of the read-only
     * schema-staging session. A replay of the completing segment
     * reads this back and returns the same `schemaRef` instead of
     * surfacing `IDEMPOTENCY_CONFLICT`. `null` for any session that
     * has not produced a schemaRef yet (ACTIVE, ABORTED, EXPIRED,
     * or a COMPLETED session whose finaliser threw).
     */
    val finalisedSchemaRef: String? = null,
    /**
     * AP 6.22: opaque single-writer claim id held by the completing
     * `tools/call` that owns the in-flight finalisation. Set when
     * the session enters [UploadSessionState.FINALIZING]; cleared
     * once the session reaches a terminal state (COMPLETED /
     * ABORTED). A reclaim after lease expiry overwrites this with
     * the new claim's id. `null` for any session that has never
     * been claimed.
     */
    val finalizingClaimId: String? = null,
    /**
     * AP 6.22: wall-clock timestamp at which the current claim was
     * acquired. Diagnostic — the actual ownership decision is
     * driven by [finalizingLeaseExpiresAt].
     */
    val finalizingClaimedAt: Instant? = null,
    /**
     * AP 6.22: wall-clock cutoff after which the current claim is
     * stale and may be reclaimed by a fresh completing call. Compared
     * against the injected `Clock` of the legacy wiring; negative
     * clock jumps must NOT extend the stored value.
     */
    val finalizingLeaseExpiresAt: Instant? = null,
    /**
     * AP 6.22: deterministic outcome record reserved before the first
     * side effect of finalisation. Survives a crash between artefact
     * write and `COMPLETED` so the next attempt replays the same
     * artefact/schemaRef instead of producing duplicates. `null` for
     * any session that has never entered FINALIZING.
     */
    val finalizationOutcome: FinalizationOutcome? = null,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: durable Bindung an die Approval-
     * Freigabe fuer policy-pflichtige Init-Pfade
     * (`uploadIntent = job_input`). Teil des SyncEffect-Scopes
     * `(tenant, caller, artifact_upload_init, approvalKey)` aus § 8.3 —
     * gleicher Tenant/Caller/Toolname/`approvalKey` plus identisches
     * `payloadFingerprint` liefert dieselbe Session.
     *
     * `null` fuer den read-only Schema-Staging-Pfad
     * (`uploadIntent = schema_staging_readonly`); dort uebernimmt
     * `clientRequestId` die Resume-Identitaet.
     */
    val approvalKey: String? = null,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: durable SHA-256 Fingerprint der Init-
     * Metadaten (`artifactKind`, `mimeType`, `sizeBytes`,
     * `checksumSha256`, `uploadIntent`, optional `targetTable`,
     * Tenant, Principal, optionaler Zielkontext). Wird beim
     * `markAwaitingApproval`/Approval-Grant gespeichert; `reserve`-
     * Replays vergleichen gegen diesen Wert (LF-010 / LF-013 / LN-009 / LN-011
     * "abweichender Payload -> IDEMPOTENCY_CONFLICT"). `null` fuer
     * Bestands-Sessions ohne policy-Pfad.
     */
    val approvalFingerprint: String? = null,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: optionaler `targetTable`-Hint fuer
     * Single-File-`job_input`-Uploads. Faellt ohne `targetTable` auf
     * den `data_import_start.table`-Pflichtpfad zurueck (LF-010 / LF-013 / LN-009 / LN-011).
     * Wird in den Approval-/Payload-Fingerprint eingerechnet (§ 4.2),
     * sodass abweichende `targetTable`-Werte zwischen Init und Import
     * via SyncEffect-/Idempotency-Conflict abgewiesen werden.
     */
    val targetTable: String? = null,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011 keeps the stable wire artifact taxonomy separate from
     * [artifactKind], because the core enum does not contain values such
     * as `seed-data` or `generic`. Finalised job-input artifacts persist
     * this value in [dev.dmigrate.server.core.artifact.ArtifactUploadMetadata].
     */
    val wireArtifactKind: String? = null,
    /**
     * Follow-up AP 2: Bundle-Init-Hint. Wenn der Caller per
     * `artifact_upload_init` ein versioniertes Seed-Bundle ankündigt
     * (LF-010 / LF-013 / LN-009 / LN-011 verbindlicher `bundleFormat`-Wert wie
     * `seed-bundle.v1.zip`), bindet diese Session-Spalte den
     * Init-Vertrag durable an das spätere Finalisat. Der
     * [dev.dmigrate.mcp.upload.JobInputFinalizer] propagiert den Wert
     * in `ArtifactUploadMetadata.bundleFormat`. `null` für
     * Single-File-Uploads.
     */
    val bundleFormat: String? = null,
    /**
     * Follow-up AP 2: per Init-Vertrag deklarierte Zieltabellen für
     * einen Mehrtabellen-Bundle-Upload. Der spätere
     * `data_import_start.tables` muss mit diesem persistierten Set
     * übereinstimmen — andernfalls liefert der Import-Handler
     * `VALIDATION_ERROR` oder bei gleichem `idempotencyKey` mit
     * abweichendem Fingerprint `IDEMPOTENCY_CONFLICT`. Die
     * Reihenfolge ist normalisiert (sortiert, Duplikate entfernt),
     * sodass `["a","b"]` und `["b","a"]` denselben Init-Fingerprint
     * ergeben. `null` für Single-File-Uploads.
     */
    val intendedTables: List<String>? = null,
)
