package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val ACME = TenantId("acme")
private val ALICE = PrincipalId("alice")
private val NOW: Instant = Instant.parse("2026-05-02T12:00:00Z")

private fun activeSession(id: String = "ups-1"): UploadSession = UploadSession(
    uploadSessionId = id,
    tenantId = ACME,
    ownerPrincipalId = ALICE,
    resourceUri = ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, id),
    artifactKind = ArtifactKind.SCHEMA,
    mimeType = "application/octet-stream",
    sizeBytes = 1024,
    segmentTotal = 1,
    checksumSha256 = "0".repeat(64),
    uploadIntent = "schema_staging_readonly",
    state = UploadSessionState.ACTIVE,
    createdAt = NOW,
    updatedAt = NOW,
    idleTimeoutAt = NOW.plusSeconds(300),
    absoluteLeaseExpiresAt = NOW.plusSeconds(3600),
)

class UploadSessionTransitionMappingTest : FunSpec({

    test("Applied: returns the new session state") {
        val store = InMemoryUploadSessionStore().apply { save(activeSession()) }
        val result = store.transitionOrThrow(activeSession(), UploadSessionState.COMPLETED, NOW)
        result.state shouldBe UploadSessionState.COMPLETED
    }

    test("IllegalTransition.from=ABORTED maps to UploadSessionAbortedException") {
        val store = racingTransitionStore(InMemoryUploadSessionStore(), UploadSessionState.ABORTED)
        shouldThrow<UploadSessionAbortedException> {
            store.transitionOrThrow(activeSession(), UploadSessionState.COMPLETED, NOW)
        }
    }

    test("IllegalTransition.from=EXPIRED maps to UploadSessionExpiredException") {
        val store = racingTransitionStore(InMemoryUploadSessionStore(), UploadSessionState.EXPIRED)
        shouldThrow<UploadSessionExpiredException> {
            store.transitionOrThrow(activeSession(), UploadSessionState.COMPLETED, NOW)
        }
    }

    test("IllegalTransition.from=COMPLETED maps to IdempotencyConflictException") {
        val store = racingTransitionStore(InMemoryUploadSessionStore(), UploadSessionState.COMPLETED)
        shouldThrow<IdempotencyConflictException> {
            store.transitionOrThrow(activeSession(), UploadSessionState.ABORTED, NOW)
        }
    }

    test("IllegalTransition.from=ACTIVE maps to InternalAgentErrorException (broken store contract)") {
        val store = racingTransitionStore(InMemoryUploadSessionStore(), UploadSessionState.ACTIVE)
        shouldThrow<InternalAgentErrorException> {
            store.transitionOrThrow(activeSession(), UploadSessionState.COMPLETED, NOW)
        }
    }

    test("NotFound maps to ResourceNotFoundException with the session's resourceUri") {
        val store = vanishingTransitionStore(InMemoryUploadSessionStore())
        val ex = shouldThrow<ResourceNotFoundException> {
            store.transitionOrThrow(activeSession(), UploadSessionState.COMPLETED, NOW)
        }
        ex.resourceUri shouldBe ServerResourceUri(ACME, ResourceKind.UPLOAD_SESSIONS, "ups-1")
    }
})
