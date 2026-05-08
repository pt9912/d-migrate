package dev.dmigrate.server.application.audit

import dev.dmigrate.server.application.error.ApplicationException
import dev.dmigrate.server.application.error.AuthRequiredException
import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.PolicyRequiredException
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.contract.MutableClock
import dev.dmigrate.server.ports.memory.InMemoryAuditSink
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * §6.8 Abnahme: `AUTH_REQUIRED`, `VALIDATION_ERROR`,
 * `TENANT_SCOPE_DENIED`, `POLICY_REQUIRED`, `POLICY_DENIED` and
 * `IDEMPOTENCY_CONFLICT` must each surface as a FAILURE audit event.
 */
class AuditEarlyFailureTest : FunSpec({

    val cases: List<Pair<ToolErrorCode, () -> ApplicationException>> = listOf(
        ToolErrorCode.AUTH_REQUIRED to { AuthRequiredException() },
        ToolErrorCode.VALIDATION_ERROR to {
            ValidationErrorException(listOf(ValidationViolation("field", "missing")))
        },
        ToolErrorCode.TENANT_SCOPE_DENIED to { TenantScopeDeniedException(TenantId("other")) },
        ToolErrorCode.POLICY_REQUIRED to { PolicyRequiredException("p1") },
        ToolErrorCode.POLICY_DENIED to { PolicyDeniedException("p1") },
        ToolErrorCode.IDEMPOTENCY_CONFLICT to { IdempotencyConflictException("fp") },
    )

    cases.forEach { (code, factory) ->
        test("${code.name} produces a FAILURE audit outcome and rethrows") {
            val sink = InMemoryAuditSink()
            val scope = AuditScope(sink, MutableClock())
            shouldThrow<ApplicationException> {
                scope.around(AuditContext(requestId = "req-${code.name}")) {
                    throw factory()
                }
            }
            val event = sink.recorded().single()
            event.outcome shouldBe AuditOutcome.FAILURE
            event.errorCode shouldBe code
        }
    }
})
