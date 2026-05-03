package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.mcp.schema.SchemaStagingFinalizer
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/**
 * AP 6.22 C7: crash, reclaim and replay scenarios on top of the
 * single-writer FINALIZING claim. These exercise paths that the
 * regular handler tests cannot easily reach without pre-staging a
 * session in `FINALIZING` with a hand-crafted lease.
 */

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")
private val PRINCIPAL = PrincipalContext(
    principalId = ALICE,
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:artifact:upload"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)
private val FIXED_NOW = Instant.parse("2026-05-02T12:00:00Z")
private val FIXED_CLOCK: Clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC)

class ArtifactUploadCrashReplayTest : FunSpec({

    test("FINALIZING + active lease + completing call: retryable Conflict, no side effects") {
        val payloadBytes = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payloadBytes)
        val (handler, sessionStore) = newFixture(stubFinalizer())
        sessionStore.save(
            sessionFinalizing(
                "ups-1",
                payloadBytes,
                totalHash,
                claimId = "claim-old",
                leaseExpiresAt = FIXED_NOW.plusSeconds(60),
            ),
        )
        shouldThrow<IdempotencyConflictException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(completingArgs("ups-1", payloadBytes)),
                    PRINCIPAL,
                ),
            )
        }
        // Session stays FINALIZING with the original claim.
        val after = sessionStore.findById(ACME, "ups-1")!!
        after.state shouldBe UploadSessionState.FINALIZING
        after.finalizingClaimId shouldBe "claim-old"
    }

    test("FINALIZING + non-final segment retry: always Conflict (no new segments accepted)") {
        val payloadBytes = "abcdefgh12345678".toByteArray()
        val totalHash = sha256Hex(payloadBytes)
        val (handler, sessionStore) = newFixture(stubFinalizer())
        sessionStore.save(
            sessionFinalizing(
                "ups-1",
                payloadBytes,
                totalHash,
                segmentTotal = 2,
                claimId = "claim-old",
                leaseExpiresAt = FIXED_NOW.plusSeconds(60),
            ),
        )
        shouldThrow<IdempotencyConflictException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(
                        nonFinalArgs(
                            "ups-1",
                            payloadBytes.copyOfRange(0, 8),
                            segmentIndex = 1,
                            segmentTotal = 2,
                        ),
                    ),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("FINALIZING + expired lease + completing call: deterministic reclaim, finishes COMPLETED") {
        val payloadBytes = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payloadBytes)
        val schemaUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "schema-fixed")
        val finalizer = SchemaStagingFinalizer { _, _, _, _, _, _ -> schemaUri }
        val (handler, sessionStore, segmentStore) = newFixture(finalizer)

        // Pre-stage a FINALIZING session whose lease has already
        // expired — the previous owner crashed without releasing it.
        // The completing segment was already written by that owner.
        val existing = sessionFinalizing(
            "ups-1",
            payloadBytes,
            totalHash,
            claimId = "claim-old",
            leaseExpiresAt = FIXED_NOW.minusSeconds(60),
        )
        sessionStore.save(existing)
        // Re-write the segment so listSegments returns it (ows-storage independent).
        segmentStore.writeSegment(
            dev.dmigrate.server.core.upload.UploadSegment(
                uploadSessionId = "ups-1",
                segmentIndex = 1,
                segmentOffset = 0,
                sizeBytes = payloadBytes.size.toLong(),
                segmentSha256 = sha256Hex(payloadBytes),
            ),
            java.io.ByteArrayInputStream(payloadBytes),
        )

        val outcome = handler.handle(
            ToolCallContext(
                "artifact_upload",
                args(completingArgs("ups-1", payloadBytes)),
                PRINCIPAL,
            ),
        )
        val payload = parsePayload(outcome)
        payload.get("uploadSessionState").asString shouldBe "COMPLETED"
        payload.get("schemaRef").asString shouldBe schemaUri.render()

        val after = sessionStore.findById(ACME, "ups-1")!!
        after.state shouldBe UploadSessionState.COMPLETED
        after.finalisedSchemaRef shouldBe schemaUri.render()
    }

    test("ABORTED + persisted FAILED outcome (VALIDATION_ERROR): replay re-throws same error class") {
        val payloadBytes = "abcdefgh".toByteArray()
        val (handler, sessionStore) = newFixture(stubFinalizer())
        val aborted = sessionAborted(
            "ups-1",
            payloadBytes,
            outcome = FinalizationOutcome(
                claimId = "claim-old",
                payloadSha256 = sha256Hex(payloadBytes),
                artifactId = "art-x",
                schemaId = "sch-x",
                format = "json",
                status = FinalizationOutcomeStatus.FAILED,
                sanitizedErrorCode = "VALIDATION_ERROR",
                sanitizedErrorMessage = "schema parse failed: malformed",
            ),
        )
        sessionStore.save(aborted)

        val ex = shouldThrow<ValidationErrorException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(completingArgs("ups-1", payloadBytes)),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.first().reason shouldBe "schema parse failed: malformed"
    }

    test("Reclaim during finaliser dispatch surfaces IdempotencyConflict on commitFinalization") {
        // Race guard for the AP-6.22 review #3 + #B: while the
        // original owner is inside finalizer.complete(), a second
        // caller reclaims the lease. The original owner's atomic
        // commitFinalization() (SUCCEEDED outcome + schemaRef +
        // COMPLETED state, all gated on claimId) must fail with
        // ClaimMismatch and surface a retryable Conflict — NOT
        // silently overwrite the new owner's state.
        val payloadBytes = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payloadBytes)
        val sessionStore = InMemoryUploadSessionStore()
        val schemaUri = ServerResourceUri(ACME, ResourceKind.SCHEMAS, "schema-fixed")
        // Mid-finalisation finaliser that simulates a parallel
        // Reclaim by overwriting the stored claim id directly.
        val racingFinalizer = SchemaStagingFinalizer { session, _, _, _, _, _ ->
            val current = sessionStore.findById(session.tenantId, session.uploadSessionId)!!
            sessionStore.save(
                current.copy(
                    finalizingClaimId = "stolen-by-reclaimer",
                    finalizingClaimedAt = FIXED_NOW,
                    finalizingLeaseExpiresAt = FIXED_NOW.plusSeconds(120),
                ),
            )
            schemaUri
        }
        val segmentStore = InMemoryUploadSegmentStore()
        val quotaStore = InMemoryQuotaStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = segmentStore,
            quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            clock = FIXED_CLOCK,
            finalizer = racingFinalizer,
        )
        // ACTIVE session that the handler will claim for finalisation.
        sessionStore.save(
            UploadSession(
                uploadSessionId = "ups-1",
                tenantId = ACME,
                ownerPrincipalId = ALICE,
                resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, "ups-1"),
                artifactKind = ArtifactKind.SCHEMA,
                mimeType = "application/octet-stream",
                sizeBytes = payloadBytes.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = totalHash,
                uploadIntent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
                state = UploadSessionState.ACTIVE,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW,
                idleTimeoutAt = FIXED_NOW.plusSeconds(300),
                absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
            ),
        )
        shouldThrow<dev.dmigrate.server.application.error.IdempotencyConflictException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(completingArgs("ups-1", payloadBytes)),
                    PRINCIPAL,
                ),
            )
        }
        // Session is still under the reclaimer's claim id — original
        // owner did NOT silently transition it to COMPLETED.
        val after = sessionStore.findById(ACME, "ups-1")!!
        after.finalizingClaimId shouldBe "stolen-by-reclaimer"
    }

    test("Non-ValidationError finaliser failures drop their message in FAILED outcome (sanitised allowlist)") {
        // Race guard for review #6: a finaliser failure carrying a
        // local path or codec stack message must NOT land in the
        // FinalizationOutcome.sanitizedErrorMessage and bleed into
        // the replay error class. Allowlist passes only
        // ValidationErrorException messages through; anything else
        // (Internal, IO, generic Runtime) collapses to null.
        val payloadBytes = "abcdefgh".toByteArray()
        val totalHash = sha256Hex(payloadBytes)
        val internalFailingFinalizer = SchemaStagingFinalizer { _, _, _, _, _, _ ->
            throw dev.dmigrate.server.application.error.InternalAgentErrorException()
        }
        val sessionStore = InMemoryUploadSessionStore()
        val handler = ArtifactUploadHandler(
            sessionStore = sessionStore,
            segmentStore = InMemoryUploadSegmentStore(),
            quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
            limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
            clock = FIXED_CLOCK,
            finalizer = internalFailingFinalizer,
        )
        sessionStore.save(
            UploadSession(
                uploadSessionId = "ups-1",
                tenantId = ACME,
                ownerPrincipalId = ALICE,
                resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, "ups-1"),
                artifactKind = ArtifactKind.SCHEMA,
                mimeType = "application/octet-stream",
                sizeBytes = payloadBytes.size.toLong(),
                segmentTotal = 1,
                checksumSha256 = totalHash,
                uploadIntent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
                state = UploadSessionState.ACTIVE,
                createdAt = FIXED_NOW,
                updatedAt = FIXED_NOW,
                idleTimeoutAt = FIXED_NOW.plusSeconds(300),
                absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
            ),
        )
        shouldThrow<dev.dmigrate.server.application.error.InternalAgentErrorException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(completingArgs("ups-1", payloadBytes)),
                    PRINCIPAL,
                ),
            )
        }
        val after = sessionStore.findById(ACME, "ups-1")!!
        val outcome = after.finalizationOutcome
            ?: error("expected FinalizationOutcome to be persisted on FAILED path")
        outcome.status shouldBe FinalizationOutcomeStatus.FAILED
        outcome.sanitizedErrorCode shouldBe "INTERNAL_ERROR"
        outcome.sanitizedErrorMessage shouldBe null
    }

    test("ABORTED without persisted FAILED outcome falls through to plain Aborted exception") {
        val payloadBytes = "abcdefgh".toByteArray()
        val (handler, sessionStore) = newFixture(stubFinalizer())
        sessionStore.save(sessionAborted("ups-1", payloadBytes, outcome = null))
        shouldThrow<UploadSessionAbortedException> {
            handler.handle(
                ToolCallContext(
                    "artifact_upload",
                    args(completingArgs("ups-1", payloadBytes)),
                    PRINCIPAL,
                ),
            )
        }
    }
})

private fun newFixture(
    finalizer: SchemaStagingFinalizer,
): Triple<ArtifactUploadHandler, InMemoryUploadSessionStore, InMemoryUploadSegmentStore> {
    val sessionStore = InMemoryUploadSessionStore()
    val segmentStore = InMemoryUploadSegmentStore()
    val quotaStore = InMemoryQuotaStore()
    val handler = ArtifactUploadHandler(
        sessionStore = sessionStore,
        segmentStore = segmentStore,
        quotaService = DefaultQuotaService(quotaStore) { Long.MAX_VALUE },
        limits = McpLimitsConfig(maxUploadSegmentBytes = 8),
        clock = FIXED_CLOCK,
        finalizer = finalizer,
    )
    return Triple(handler, sessionStore, segmentStore)
}

private fun stubFinalizer(): SchemaStagingFinalizer = SchemaStagingFinalizer { session, _, _, _, _, _ ->
    ServerResourceUri(session.tenantId, ResourceKind.SCHEMAS, "schema-fixed")
}

private fun sessionFinalizing(
    id: String,
    payload: ByteArray,
    checksumSha256: String,
    segmentTotal: Int = 1,
    claimId: String,
    leaseExpiresAt: Instant,
): UploadSession = UploadSession(
    uploadSessionId = id,
    tenantId = ACME,
    ownerPrincipalId = ALICE,
    resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, id),
    artifactKind = ArtifactKind.SCHEMA,
    mimeType = "application/octet-stream",
    sizeBytes = payload.size.toLong(),
    segmentTotal = segmentTotal,
    checksumSha256 = checksumSha256,
    uploadIntent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
    state = UploadSessionState.FINALIZING,
    createdAt = FIXED_NOW.minusSeconds(300),
    updatedAt = FIXED_NOW.minusSeconds(60),
    idleTimeoutAt = FIXED_NOW.plusSeconds(300),
    absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
    bytesReceived = payload.size.toLong(),
    finalizingClaimId = claimId,
    finalizingClaimedAt = FIXED_NOW.minusSeconds(60),
    finalizingLeaseExpiresAt = leaseExpiresAt,
)

private fun sessionAborted(
    id: String,
    payload: ByteArray,
    outcome: FinalizationOutcome?,
): UploadSession = UploadSession(
    uploadSessionId = id,
    tenantId = ACME,
    ownerPrincipalId = ALICE,
    resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, id),
    artifactKind = ArtifactKind.SCHEMA,
    mimeType = "application/octet-stream",
    sizeBytes = payload.size.toLong(),
    segmentTotal = 1,
    checksumSha256 = sha256Hex(payload),
    uploadIntent = ArtifactUploadInitHandler.INTENT_SCHEMA_STAGING_READONLY,
    state = UploadSessionState.ABORTED,
    createdAt = FIXED_NOW.minusSeconds(300),
    updatedAt = FIXED_NOW.minusSeconds(60),
    idleTimeoutAt = FIXED_NOW.plusSeconds(300),
    absoluteLeaseExpiresAt = FIXED_NOW.plusSeconds(3600),
    bytesReceived = payload.size.toLong(),
    finalizationOutcome = outcome,
)

private fun completingArgs(sessionId: String, payload: ByteArray): String {
    val b64 = Base64.getEncoder().encodeToString(payload)
    return """
        {
          "uploadSessionId":"$sessionId",
          "segmentIndex":1,
          "segmentOffset":0,
          "segmentTotal":1,
          "isFinalSegment":true,
          "segmentSha256":"${sha256Hex(payload)}",
          "contentBase64":"$b64"
        }
    """.trimIndent()
}

private fun nonFinalArgs(sessionId: String, segmentBytes: ByteArray, segmentIndex: Int, segmentTotal: Int): String {
    val b64 = Base64.getEncoder().encodeToString(segmentBytes)
    val offset = (segmentIndex - 1) * 8L
    return """
        {
          "uploadSessionId":"$sessionId",
          "segmentIndex":$segmentIndex,
          "segmentOffset":$offset,
          "segmentTotal":$segmentTotal,
          "isFinalSegment":false,
          "segmentSha256":"${sha256Hex(segmentBytes)}",
          "contentBase64":"$b64"
        }
    """.trimIndent()
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
