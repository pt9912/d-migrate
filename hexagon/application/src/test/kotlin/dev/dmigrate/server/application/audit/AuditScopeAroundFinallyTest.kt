package dev.dmigrate.server.application.audit

import dev.dmigrate.server.application.error.AuthRequiredException
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.contract.MutableClock
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AuditScopeAroundFinallyTest : FunSpec({

    data class Harness(val scope: AuditScope, val sink: InMemoryAuditSink, val clock: MutableClock)

    fun harness(): Harness {
        val clock = MutableClock()
        val sink = InMemoryAuditSink()
        return Harness(AuditScope(sink, clock), sink, clock)
    }

    val context = AuditContext(
        requestId = "req-1",
        toolName = "data.export",
        tenantId = TenantId("acme"),
        principalId = PrincipalId("alice"),
    )

    test("successful block emits SUCCESS event with late-bound fields populated") {
        val h = harness()
        val result = h.scope.around(context) { fields ->
            fields.payloadFingerprint = "fp-1"
            fields.resourceRefs = listOf("dmigrate://tenants/acme/jobs/j1")
            h.clock.advance(2)
            "ok"
        }
        result shouldBe "ok"
        val event = h.sink.recorded().single()
        event.requestId shouldBe "req-1"
        event.outcome shouldBe AuditOutcome.SUCCESS
        event.errorCode shouldBe null
        event.payloadFingerprint shouldBe "fp-1"
        event.resourceRefs shouldBe listOf("dmigrate://tenants/acme/jobs/j1")
        event.durationMs shouldBe 2_000L
    }

    test("ApplicationException maps to FAILURE + ex.code and is rethrown") {
        val h = harness()
        shouldThrow<AuthRequiredException> {
            h.scope.around(context) { throw AuthRequiredException() }
        }
        val event = h.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.AUTH_REQUIRED
    }

    test("unknown Throwable maps to INTERNAL_AGENT_ERROR and is rethrown") {
        val h = harness()
        shouldThrow<IllegalStateException> {
            h.scope.around(context) { throw IllegalStateException("boom") }
        }
        val event = h.sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
    }

    test("missing principal stays null — no fabricated principal in audit") {
        val h = harness()
        h.scope.around(AuditContext(requestId = "req-2")) { }
        val event = h.sink.recorded().single()
        event.principalId shouldBe null
        event.tenantId shouldBe null
        event.toolName shouldBe null
    }

    test("event is emitted even when block populates no fields (early failure)") {
        val h = harness()
        shouldThrow<AuthRequiredException> {
            h.scope.around(context) { throw AuthRequiredException() }
        }
        val event = h.sink.recorded().single()
        event.payloadFingerprint shouldBe null
        event.resourceRefs shouldBe emptyList()
    }

    test("durationMs reflects the injected clock advance") {
        val h = harness()
        h.scope.around(context) { h.clock.advance(5) }
        h.sink.recorded().single().durationMs shouldBe 5_000L
    }

    test("resourceRefs are scrubbed before emission") {
        val h = harness()
        h.scope.around(context) { fields ->
            fields.resourceRefs = listOf(
                "jdbc:postgresql://h?password=secret123",
                "dmigrate://tenants/acme/jobs/j1",
            )
        }
        val refs = h.sink.recorded().single().resourceRefs
        refs[0].contains("secret123") shouldBe false
        refs[1] shouldBe "dmigrate://tenants/acme/jobs/j1"
    }
})
