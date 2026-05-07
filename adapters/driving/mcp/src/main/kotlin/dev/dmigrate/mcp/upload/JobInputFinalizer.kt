package dev.dmigrate.mcp.upload

import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.time.Clock
import java.time.Duration

/**
 * Phase F § 8.5 (F.5 1/3) — Finaliser fuer policy-pflichtige
 * `uploadIntent=job_input`-Sessions.
 *
 * Aufgerufen vom [dev.dmigrate.mcp.registry.StreamingFinalizer] nach
 * dem `FINALIZING`-Claim und der Streaming-Assembly. Im Gegensatz
 * zum [dev.dmigrate.mcp.schema.SchemaStagingFinalizer] gibt es:
 *
 * - kein Schema-Parse, keine Validator-Pruefung, keine `schemaRef`-
 *   Materialisierung — `job_input` liefert opake Bytes (CSV/JSONL/
 *   Parquet/etc.) fuer den nachgelagerten Import-Worker.
 * - Identitaet ueber den deterministischen `artifactId` aus
 *   `(tenant, session, payloadSha, format)` — Replay landet
 *   idempotent auf `WriteArtifactOutcome.AlreadyExists`.
 * - durable [ArtifactRecord] mit `kind = session.artifactKind`
 *   (typischerweise `UPLOAD_INPUT`), `contentType = session.mimeType`,
 *   `filename` aus session+artifactId, sodass
 *   `artifact_chunk_get` / `artifact_list` die Bytes nach der
 *   Finalisierung lesen kann (Plan § 8.5: "Upload-Metadaten sind
 *   nach Finalisierung persistent aus dem Artifact-Store ...
 *   lesbar").
 *
 * Idempotenz-Vertrag entspricht AP 6.22:
 * - `WriteArtifactOutcome.AlreadyExists` mit gleichem SHA + size ist
 *   ein No-Op-Replay (selber `artifactId` aus dem deterministischen
 *   Material).
 * - Differierende Stored-Werte unter gleichem `artifactId` sind ein
 *   harter interner Konflikt -> [InternalAgentErrorException].
 */
fun interface JobInputFinalizer {

    fun complete(
        session: UploadSession,
        principal: PrincipalContext,
        payload: AssembledUploadPayload,
        artifactId: String,
        format: String,
    ): ServerResourceUri
}

/**
 * Production-Implementation: Streaming-Write der Artefakt-Bytes via
 * [ArtifactContentStore] + [ArtifactStore]-Registrierung.
 *
 * Speichert keine Schema-Daten und ruft keinen Validator —
 * `job_input`-Bytes werden vom nachgelagerten Import-Worker
 * (F.7 / F.8) als opake Datenquelle interpretiert.
 */
class DefaultJobInputFinalizer(
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val clock: Clock,
    private val artifactTtl: Duration = ARTIFACT_TTL,
) : JobInputFinalizer {

    override fun complete(
        session: UploadSession,
        principal: PrincipalContext,
        payload: AssembledUploadPayload,
        artifactId: String,
        format: String,
    ): ServerResourceUri {
        materialiseArtifact(session, principal, payload, artifactId)
        return ServerResourceUri(session.tenantId, ResourceKind.ARTIFACTS, artifactId)
    }

    private fun materialiseArtifact(
        session: UploadSession,
        principal: PrincipalContext,
        payload: AssembledUploadPayload,
        artifactId: String,
    ) {
        val outcome = payload.openStream().use { source ->
            artifactContentStore.write(
                artifactId = artifactId,
                source = source,
                expectedSizeBytes = payload.sizeBytes,
            )
        }
        when (outcome) {
            is WriteArtifactOutcome.Stored -> Unit
            is WriteArtifactOutcome.AlreadyExists -> {
                // Replay: deterministischer artifactId, gleicher SHA +
                // gleiche size -> No-Op. Drift = harter Konflikt.
                if (outcome.existingSha256 != payload.sha256 ||
                    outcome.existingSizeBytes != payload.sizeBytes
                ) {
                    throw InternalAgentErrorException()
                }
            }
            is WriteArtifactOutcome.SizeMismatch,
            is WriteArtifactOutcome.Conflict -> throw InternalAgentErrorException()
        }
        val now = clock.instant()
        val resourceUri = ServerResourceUri(session.tenantId, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    // Source-Session-Id ist im filename eingebettet —
                    // erlaubt Operator-Diagnose ohne neue Spalte.
                    filename = "upload-${session.uploadSessionId}-$artifactId.bin",
                    contentType = session.mimeType,
                    sizeBytes = payload.sizeBytes,
                    sha256 = payload.sha256,
                    createdAt = now,
                    expiresAt = now.plus(artifactTtl),
                ),
                kind = session.artifactKind,
                tenantId = session.tenantId,
                ownerPrincipalId = principal.principalId,
                // job_input-Artefakte sind tenant-sichtbar — der
                // nachgelagerte Import-Worker laeuft als
                // Tenant-Service-Account und braucht Lesezugriff.
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
                uploadMetadata = ArtifactUploadMetadata(
                    artifactId = artifactId,
                    resourceUri = resourceUri.render(),
                    uploadIntent = session.uploadIntent,
                    wireArtifactKind = session.wireArtifactKind ?: "seed-data",
                    contentType = session.mimeType,
                    format = inferFormat(session.mimeType),
                    targetTable = session.targetTable,
                    sourceUploadSessionId = session.uploadSessionId,
                    policyFingerprint = session.approvalFingerprint,
                    sizeBytes = payload.sizeBytes,
                    sha256 = payload.sha256,
                ),
            ),
        )
    }

    private fun inferFormat(mimeType: String): String? =
        when (mimeType.lowercase().substringBefore(";").trim()) {
            "text/csv", "application/csv", "application/vnd.ms-excel" -> "csv"
            "application/json", "text/json", "application/x-ndjson" -> "json"
            "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml" -> "yaml"
            else -> null
        }

    companion object {
        val ARTIFACT_TTL: Duration = Duration.ofDays(7)
    }
}
