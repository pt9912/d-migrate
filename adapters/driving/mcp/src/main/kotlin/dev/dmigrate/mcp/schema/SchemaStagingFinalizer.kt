package dev.dmigrate.mcp.schema

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * AP 6.9: finalises a COMPLETED read-only schema-staging session per
 * `ImpPlan-0.9.6-C.md` §6.9 and `spec/ki-mcp.md` §5.3 line 421-426.
 *
 * Called by `ArtifactUploadHandler` once `isFinalSegment=true` has
 * passed every AP-6.8 invariant (segments complete, cumulative size
 * matches, total SHA-256 matches). The finaliser:
 *
 * 1. parses the assembled bytes via the existing format codec
 * 2. validates the parsed `SchemaDefinition` via [SchemaValidator]
 * 3. on success: materialises an immutable artefact in
 *    [ArtifactContentStore], registers the matching [ArtifactRecord]
 *    and a tenant-scoped [SchemaIndexEntry], and returns the
 *    `schemaRef` URI for the wire response
 * 4. on parse/validation failure: throws `VALIDATION_ERROR` with
 *    structured findings; AP 6.8 catches the throw, rolls the
 *    session to `ABORTED` so the bytes get cleaned up, and bubbles
 *    the typed envelope to the client
 *
 * Codec/IO exceptions are sanitised to `VALIDATION_ERROR` so the
 * client never sees a raw Jackson stack trace across the trust
 * boundary (§6.4 acceptance is reused here).
 */
fun interface SchemaStagingFinalizer {

    /**
     * Finalises [session] using [assembledBytes] (the rebuilt
     * payload from all segments in index order). Returns the
     * registered `schemaRef` URI on success; throws on failure.
     */
    fun complete(
        session: UploadSession,
        principal: PrincipalContext,
        assembledBytes: ByteArray,
        format: String,
    ): ServerResourceUri
}

/**
 * Production implementation: parses + validates with the existing
 * codecs and validator, then materialises through the Phase-A
 * artefact ports.
 */
class DefaultSchemaStagingFinalizer(
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val validator: SchemaValidator,
    private val clock: Clock,
    private val artifactTtl: Duration = ARTIFACT_TTL,
    private val schemaTtl: Duration = SCHEMA_TTL,
    private val artifactIdGenerator: () -> String = ::generateArtifactId,
    private val schemaIdGenerator: () -> String = ::generateSchemaId,
) : SchemaStagingFinalizer {

    override fun complete(
        session: UploadSession,
        principal: PrincipalContext,
        assembledBytes: ByteArray,
        format: String,
    ): ServerResourceUri {
        val schemaDef = parseSchema(assembledBytes, format)
        val result = validator.validate(schemaDef)
        if (!result.isValid) {
            throw ValidationErrorException(
                violations = result.errors.map {
                    ValidationViolation(it.objectPath.ifBlank { "schema" }, "[${it.code}] ${it.message}")
                },
            )
        }
        val artifactId = materialiseArtifact(session, principal, assembledBytes)
        return registerSchemaRef(session, principal, schemaDef, artifactId)
    }

    @Suppress("SwallowedException")
    private fun parseSchema(bytes: ByteArray, format: String): SchemaDefinition {
        val codec = try {
            SchemaFileResolver.codecForFormat(format)
        } catch (e: IllegalArgumentException) {
            // SwallowedException: format hint is client-supplied; the
            // sanitised message is enough.
            throw ValidationErrorException(
                listOf(ValidationViolation("format", e.message ?: "unsupported format '$format'")),
            )
        }
        return readBytesAsSchema(codec, bytes)
    }

    @Suppress("SwallowedException")
    private fun readBytesAsSchema(codec: SchemaCodec, bytes: ByteArray): SchemaDefinition = try {
        ByteArrayInputStream(bytes).use { codec.read(it) }
    } catch (e: IOException) {
        // SwallowedException: same trust-boundary rule as AP 6.4 —
        // never leak the raw codec stack across the wire.
        throw ValidationErrorException(
            listOf(ValidationViolation("schema", "failed to read schema content: ${e.message}")),
        )
    } catch (e: RuntimeException) {
        throw ValidationErrorException(
            listOf(ValidationViolation("schema", "schema parse failed: ${e.message ?: "malformed"}")),
        )
    }

    private fun materialiseArtifact(
        session: UploadSession,
        principal: PrincipalContext,
        bytes: ByteArray,
    ): String {
        val artifactId = artifactIdGenerator()
        when (
            artifactContentStore.write(
                artifactId = artifactId,
                source = ByteArrayInputStream(bytes),
                expectedSizeBytes = bytes.size.toLong(),
            )
        ) {
            is WriteArtifactOutcome.Stored,
            is WriteArtifactOutcome.AlreadyExists -> Unit
            is WriteArtifactOutcome.SizeMismatch,
            is WriteArtifactOutcome.Conflict -> throw InternalAgentErrorException()
        }
        val now = clock.instant()
        val sha256 = sha256Hex(bytes)
        val resourceUri = ServerResourceUri(session.tenantId, ResourceKind.ARTIFACTS, artifactId)
        artifactStore.save(
            ArtifactRecord(
                managedArtifact = ManagedArtifact(
                    artifactId = artifactId,
                    filename = "schema-${session.uploadSessionId}.json",
                    contentType = "application/json",
                    sizeBytes = bytes.size.toLong(),
                    sha256 = sha256,
                    createdAt = now,
                    expiresAt = now.plus(artifactTtl),
                ),
                kind = ArtifactKind.SCHEMA,
                tenantId = session.tenantId,
                ownerPrincipalId = principal.principalId,
                visibility = JobVisibility.TENANT,
                resourceUri = resourceUri,
            ),
        )
        return artifactId
    }

    private fun registerSchemaRef(
        session: UploadSession,
        principal: PrincipalContext,
        schema: SchemaDefinition,
        artifactRef: String,
    ): ServerResourceUri {
        val schemaId = schemaIdGenerator()
        val now = clock.instant()
        val schemaUri = ServerResourceUri(session.tenantId, ResourceKind.SCHEMAS, schemaId)
        val displayName = schema.name.ifBlank { schemaId }
        schemaStore.save(
            SchemaIndexEntry(
                schemaId = schemaId,
                tenantId = session.tenantId,
                resourceUri = schemaUri,
                artifactRef = artifactRef,
                displayName = displayName,
                createdAt = now,
                expiresAt = now.plus(schemaTtl),
                jobRef = null,
                labels = mapOf(
                    "uploadSessionId" to session.uploadSessionId,
                    "ownerPrincipalId" to principal.principalId.value,
                ),
            ),
        )
        return schemaUri
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        val ARTIFACT_TTL: Duration = Duration.ofDays(7)
        val SCHEMA_TTL: Duration = Duration.ofDays(7)

        private fun generateArtifactId(): String =
            "art-${UUID.randomUUID().toString().replace("-", "").take(16)}"

        private fun generateSchemaId(): String =
            "schema-${UUID.randomUUID().toString().replace("-", "").take(16)}"
    }
}
