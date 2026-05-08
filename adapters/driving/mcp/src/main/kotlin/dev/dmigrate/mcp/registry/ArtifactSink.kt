package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Shared sink for read-only Phase-C tool fallbacks
 * (`schema_generate` AP 6.5, `schema_compare` AP 6.6, AP 6.13
 * centralised output). When a tool's response would exceed
 * `maxToolResponseBytes`, the consumer hands the rendered bytes here
 * and gets back a [ServerResourceUri] pointing at the persisted
 * artefact.
 *
 * Visibility is fixed to [JobVisibility.TENANT] — read-only outputs
 * are addressable by the caller's whole tenant via the artifact
 * resource URI; `OWNER` would silently break peer-discovery for the
 * same tenant. Approval-policy is intentionally NOT consulted: this
 * is the read-only counterpart to AP 6.7-6.10's policy-pflichtigen
 * uploads, which take a different path.
 */
internal class ArtifactSink(
    private val artifactStore: ArtifactStore,
    private val contentStore: ArtifactContentStore,
    private val clock: Clock,
    private val ttl: Duration = DEFAULT_TTL,
) {

    /**
     * Persists [content] under a freshly minted artifact id, registers
     * a tenant-visible [ArtifactRecord], and returns the resource URI
     * the tool response advertises as `artifactRef`. The returned URI
     * is stable across retries with the same content (the underlying
     * [ArtifactContentStore] dedupes by sha256).
     *
     * @throws PayloadTooLargeException when the artefact size exceeds
     *  `maxArtifactUploadBytes` — handlers should catch and downgrade
     *  to a structured `PAYLOAD_TOO_LARGE` envelope.
     */
    fun writeReadOnly(
        principal: PrincipalContext,
        kind: ArtifactKind,
        contentType: String,
        filename: String,
        content: ByteArray,
        maxArtifactBytes: Long,
    ): ServerResourceUri {
        if (content.size.toLong() > maxArtifactBytes) {
            throw PayloadTooLargeException(
                actualBytes = content.size.toLong(),
                maxBytes = maxArtifactBytes,
            )
        }
        val artifactId = "art-${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val sha256 = sha256Hex(content)
        when (contentStore.write(artifactId, ByteArrayInputStream(content), content.size.toLong())) {
            is WriteArtifactOutcome.Stored,
            is WriteArtifactOutcome.AlreadyExists -> Unit
            // Both branches are "this cannot happen by construction":
            // size came from a `ByteArray.size` and the artifactId is
            // a freshly minted UUID. Surface as INTERNAL_AGENT_ERROR
            // so the dispatch path renders a sane envelope without
            // leaking the raw outcome across the trust boundary.
            is WriteArtifactOutcome.SizeMismatch,
            is WriteArtifactOutcome.Conflict -> throw InternalAgentErrorException()
        }
        val now = clock.instant()
        val resourceUri = ServerResourceUri(principal.effectiveTenantId, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = filename,
                    contentType = contentType,
                    sizeBytes = content.size.toLong(),
                    sha256 = sha256,
                    createdAt = now,
                    expiresAt = now.plus(ttl),
                ),
                kind = kind,
                tenantId = principal.effectiveTenantId,
                ownerPrincipalId = principal.principalId,
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
            ),
        )
        return resourceUri
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofHours(24)
    }
}
