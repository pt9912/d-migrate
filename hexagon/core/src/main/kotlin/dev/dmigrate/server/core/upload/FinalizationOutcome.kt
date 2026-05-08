package dev.dmigrate.server.core.upload

/**
 * AP 6.22: deterministic outcome anchor for an upload-session
 * finalisation. Reserved on the [UploadSession] before the first
 * side effect (artefact write, schema register) so a crash between
 * those steps and the final `COMPLETED` transition can be replayed
 * without producing a second artefact or schemaRef.
 *
 * `artifactId` and `schemaId` are derived deterministically from
 * tenant + uploadSessionId + total payload SHA + format so the
 * record can be reconstructed after a crash. Conflicts on the same
 * deterministic id with a different SHA / different schema content
 * are hard internal inconsistency — never silently re-materialised.
 *
 * @property claimId opaque single-writer claim id of the completing
 *   `tools/call` that owns this finalisation attempt. Reclaim after
 *   a stale FINALIZING lease replaces both the [claimId] on the
 *   session and the one persisted here.
 * @property payloadSha256 SHA-256 of the assembled payload bytes;
 *   matches the `WriteArtifactOutcome.Stored.sha256` once the
 *   artefact has been written.
 * @property artifactId deterministic id under which the artefact is
 *   (or will be) registered in `ArtifactContentStore`/`ArtifactStore`.
 * @property schemaId deterministic id under which the schema is (or
 *   will be) registered in `SchemaStore`. May be `null` for upload
 *   intents that do not produce a schema (e.g. binary uploads in
 *   future phases).
 * @property format wire format token (e.g. `json`, `yaml`) used both
 *   in the deterministic id derivation and in the resulting
 *   schema/artefact metadata.
 * @property status [FinalizationOutcomeStatus.IN_PROGRESS] while the
 *   side effects are running, then [FinalizationOutcomeStatus.SUCCEEDED]
 *   or [FinalizationOutcomeStatus.FAILED]. Replays of a SUCCEEDED
 *   outcome reuse the artefact/schemaRef; replays of a FAILED outcome
 *   surface the same sanitised error class.
 * @property sanitizedErrorCode short, scrubbed error code for FAILED
 *   outcomes — never leaks local paths or user payload bytes.
 *   `null` for IN_PROGRESS / SUCCEEDED.
 * @property sanitizedErrorMessage short, scrubbed human-readable
 *   message for FAILED outcomes. `null` for IN_PROGRESS / SUCCEEDED.
 */
data class FinalizationOutcome(
    val claimId: String,
    val payloadSha256: String,
    val artifactId: String,
    val schemaId: String?,
    val format: String,
    val status: FinalizationOutcomeStatus,
    val sanitizedErrorCode: String? = null,
    val sanitizedErrorMessage: String? = null,
)

enum class FinalizationOutcomeStatus {
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
}
