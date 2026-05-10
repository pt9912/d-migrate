package dev.dmigrate.server.core.audit

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Construction-coverage for [AuditEvent] and [AuditOutcome]. Pure
 * data carriers; the test exercises every nullable field path so the
 * auto-generated `equals`/`hashCode`/`copy`/`toString` count toward
 * the kover line coverage.
 */
class AuditEventTest : FunSpec({

    val startedAt = Instant.parse("2026-05-10T12:00:00Z")

    test("AuditOutcome enum exposes SUCCESS + FAILURE") {
        AuditOutcome.entries.toSet() shouldBe setOf(AuditOutcome.SUCCESS, AuditOutcome.FAILURE)
    }

    test("AuditEvent constructs with only the required fields") {
        val ev = AuditEvent(
            requestId = "req-1",
            outcome = AuditOutcome.SUCCESS,
            startedAt = startedAt,
        )
        ev.toolName shouldBe null
        ev.tenantId shouldBe null
        ev.principalId shouldBe null
        ev.errorCode shouldBe null
        ev.payloadFingerprint shouldBe null
        ev.resourceRefs shouldBe emptyList()
        ev.durationMs shouldBe null
    }

    test("AuditEvent populates every optional field on the failure path") {
        val ev = AuditEvent(
            requestId = "req-2",
            outcome = AuditOutcome.FAILURE,
            startedAt = startedAt,
            toolName = "ai.test",
            tenantId = TenantId("t-1"),
            principalId = PrincipalId("p-1"),
            errorCode = ToolErrorCode.INTERNAL_AGENT_ERROR,
            payloadFingerprint = "pfp-1",
            resourceRefs = listOf("ref-a", "ref-b"),
            durationMs = 123L,
        )
        ev.toolName shouldBe "ai.test"
        ev.tenantId shouldBe TenantId("t-1")
        ev.errorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
        ev.durationMs shouldBe 123L
    }

    test("equals + hashCode follow data-class semantics") {
        val a = AuditEvent("r", AuditOutcome.SUCCESS, startedAt)
        val b = AuditEvent("r", AuditOutcome.SUCCESS, startedAt)
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
    }
})
