package dev.dmigrate.server.application.audit

import dev.dmigrate.server.application.error.AuthRequiredException
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.contract.MutableClock
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the §6.8 outcome-mapping contract:
 *   block returns                 -> SUCCESS, errorCode = null
 *   ApplicationException          -> FAILURE, errorCode = ex.code
 *   any other Throwable           -> FAILURE, errorCode = INTERNAL_AGENT_ERROR
 */
class AuditOutcomeMappingTest : FunSpec({

    fun harness(): Pair<AuditScope, InMemoryAuditSink> {
        val sink = InMemoryAuditSink()
        return AuditScope(sink, MutableClock()) to sink
    }

    val ctx = AuditContext(requestId = "req-x")

    test("block returns -> SUCCESS, no errorCode") {
        val (scope, sink) = harness()
        scope.around(ctx) { 1 + 1 }
        val event = sink.recorded().single()
        event.outcome shouldBe AuditOutcome.SUCCESS
        event.errorCode shouldBe null
    }

    test("ApplicationException -> FAILURE with the exception's code") {
        val (scope, sink) = harness()
        shouldThrow<AuthRequiredException> {
            scope.around(ctx) { throw AuthRequiredException() }
        }
        val event = sink.recorded().single()
        event.outcome shouldBe AuditOutcome.FAILURE
        event.errorCode shouldBe ToolErrorCode.AUTH_REQUIRED
    }

    test("RuntimeException -> FAILURE with INTERNAL_AGENT_ERROR") {
        val (scope, sink) = harness()
        shouldThrow<RuntimeException> {
            scope.around(ctx) { throw RuntimeException("boom") }
        }
        sink.recorded().single().errorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
    }

    test("Error (not Exception) -> FAILURE with INTERNAL_AGENT_ERROR") {
        val (scope, sink) = harness()
        shouldThrow<OutOfMemoryError> {
            scope.around(ctx) { throw OutOfMemoryError("synthetic") }
        }
        sink.recorded().single().errorCode shouldBe ToolErrorCode.INTERNAL_AGENT_ERROR
    }

    test("the exact same throwable instance is rethrown (not wrapped)") {
        val (scope, _) = harness()
        val original = AuthRequiredException()
        val thrown = shouldThrow<AuthRequiredException> {
            scope.around(ctx) { throw original }
        }
        (thrown === original) shouldBe true
    }
})
