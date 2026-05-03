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
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaRegisterOutcome
import dev.dmigrate.server.ports.SchemaStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import java.io.IOException
import java.time.Clock
import java.time.Duration

/**
 * AP 6.9 + AP 6.22: finalises a `FINALIZING` read-only schema-staging
 * session per `ImpPlan-0.9.6-C.md` §6.9 + §6.22.
 *
 * Called by `StreamingFinalizer` (the upload handler's claim-and-
 * finalise pipeline) after the streaming-assembly spool has produced
 * a re-openable [AssembledUploadPayload]. The finaliser:
 *
 * 1. parses the assembled bytes via the existing format codec —
 *    streaming via [AssembledUploadPayload.openStream]
 * 2. validates the parsed `SchemaDefinition` via [SchemaValidator]
 * 3. on success: materialises an immutable artefact in
 *    [ArtifactContentStore] under the supplied deterministic
 *    [artifactId], registers the matching [ArtifactRecord], and
 *    idempotently registers the [SchemaIndexEntry] under the
 *    deterministic [schemaId]; returns the `schemaRef` URI
 * 4. on parse/validation failure: throws `VALIDATION_ERROR` with
 *    structured findings; `StreamingFinalizer` catches the throw,
 *    persists a sanitised [dev.dmigrate.server.core.upload.FinalizationOutcome]
 *    and rolls the session to `ABORTED`
 *
 * AP 6.22 idempotency: a replay carrying the same [artifactId] /
 * [schemaId] (because the deterministic derivation produces the same
 * IDs for the same payload) is a no-op — `ArtifactContentStore.write`
 * returns `AlreadyExists` and `SchemaStore.register` returns
 * `AlreadyRegistered`, both of which are accepted as success here.
 * Differing SHA / size / `artifactRef` under the same id is hard
 * internal inconsistency and surfaces as
 * [InternalAgentErrorException].
 */
fun interface SchemaStagingFinalizer {

    fun complete(
        session: UploadSession,
        principal: PrincipalContext,
        payload: AssembledUploadPayload,
        artifactId: String,
        schemaId: String,
        format: String,
    ): ServerResourceUri
}

/**
 * Production implementation: parses + validates with the existing
 * codecs and validator, then materialises through the Phase-A
 * artefact ports. Streams the payload twice via
 * [AssembledUploadPayload.openStream] so the heap stays bounded by
 * the codec / store buffer sizes, not by the payload size.
 */
class DefaultSchemaStagingFinalizer(
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val schemaStore: SchemaStore,
    private val validator: SchemaValidator,
    private val clock: Clock,
    private val artifactTtl: Duration = ARTIFACT_TTL,
    private val schemaTtl: Duration = SCHEMA_TTL,
) : SchemaStagingFinalizer {

    override fun complete(
        session: UploadSession,
        principal: PrincipalContext,
        payload: AssembledUploadPayload,
        artifactId: String,
        schemaId: String,
        format: String,
    ): ServerResourceUri {
        val schemaDef = parseSchema(payload, format)
        val result = validator.validate(schemaDef)
        if (!result.isValid) {
            throw ValidationErrorException(
                violations = result.errors.map {
                    ValidationViolation(it.objectPath.ifBlank { "schema" }, "[${it.code}] ${it.message}")
                },
            )
        }
        materialiseArtifact(session, principal, payload, artifactId)
        return registerSchemaRef(session, principal, schemaDef, artifactId, schemaId)
    }

    @Suppress("SwallowedException")
    private fun parseSchema(payload: AssembledUploadPayload, format: String): SchemaDefinition {
        val codec = try {
            SchemaFileResolver.codecForFormat(format)
        } catch (e: IllegalArgumentException) {
            // SwallowedException: format hint is client-supplied; the
            // sanitised message is enough.
            throw ValidationErrorException(
                listOf(ValidationViolation("format", e.message ?: "unsupported format '$format'")),
            )
        }
        return readPayloadAsSchema(codec, payload)
    }

    @Suppress("SwallowedException")
    private fun readPayloadAsSchema(codec: SchemaCodec, payload: AssembledUploadPayload): SchemaDefinition = try {
        payload.openStream().use { codec.read(it) }
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
        payload: AssembledUploadPayload,
        artifactId: String,
    ) {
        // AP 6.22: idempotent — `AlreadyExists` is accepted only when
        // the persisted SHA + size match the supplied payload (the
        // deterministic [artifactId] derivation guarantees the SHA
        // match, but a defensive size cross-check catches store
        // metadata drift).
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
                    filename = "schema-${session.uploadSessionId}.json",
                    contentType = "application/json",
                    sizeBytes = payload.sizeBytes,
                    sha256 = payload.sha256,
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
    }

    private fun registerSchemaRef(
        session: UploadSession,
        principal: PrincipalContext,
        schema: SchemaDefinition,
        artifactRef: String,
        schemaId: String,
    ): ServerResourceUri {
        val now = clock.instant()
        val schemaUri = ServerResourceUri(session.tenantId, ResourceKind.SCHEMAS, schemaId)
        val displayName = schema.name.ifBlank { schemaId }
        val entry = SchemaIndexEntry(
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
        )
        return when (val outcome = schemaStore.register(entry)) {
            is SchemaRegisterOutcome.Registered -> outcome.entry.resourceUri
            // AP 6.22: replay of the same deterministic schemaId with
            // the same artefact is idempotent — return the previously
            // registered URI verbatim.
            is SchemaRegisterOutcome.AlreadyRegistered -> outcome.existing.resourceUri
            is SchemaRegisterOutcome.Conflict -> throw InternalAgentErrorException()
        }
    }

    companion object {
        val ARTIFACT_TTL: Duration = Duration.ofDays(7)
        val SCHEMA_TTL: Duration = Duration.ofDays(7)
    }
}
