package dev.dmigrate.server.core.error

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ToolErrorEnvelopeTest : FunSpec({

    test("envelope holds code, message, details and requestId") {
        val envelope = ToolErrorEnvelope(
            code = ToolErrorCode.VALIDATION_ERROR,
            message = "field 'tenantId' is required",
            details = listOf(
                ToolErrorDetail("field", "tenantId"),
                ToolErrorDetail("constraint", "non-blank"),
            ),
            requestId = "req_42",
        )
        envelope.code shouldBe ToolErrorCode.VALIDATION_ERROR
        envelope.message shouldBe "field 'tenantId' is required"
        envelope.details shouldBe listOf(
            ToolErrorDetail("field", "tenantId"),
            ToolErrorDetail("constraint", "non-blank"),
        )
        envelope.requestId shouldBe "req_42"
    }

    test("details default to empty and requestId to null") {
        val envelope = ToolErrorEnvelope(
            code = ToolErrorCode.INTERNAL_AGENT_ERROR,
            message = "boom",
        )
        envelope.details shouldBe emptyList()
        envelope.requestId shouldBe null
    }

    test("ToolErrorCode covers the 18 codes mandated by docs/ki-mcp.md") {
        ToolErrorCode.entries.size shouldBe 18
        ToolErrorCode.entries.toSet() shouldBe setOf(
            ToolErrorCode.AUTH_REQUIRED,
            ToolErrorCode.FORBIDDEN_PRINCIPAL,
            ToolErrorCode.POLICY_REQUIRED,
            ToolErrorCode.POLICY_DENIED,
            ToolErrorCode.IDEMPOTENCY_KEY_REQUIRED,
            ToolErrorCode.IDEMPOTENCY_CONFLICT,
            ToolErrorCode.RESOURCE_NOT_FOUND,
            ToolErrorCode.VALIDATION_ERROR,
            ToolErrorCode.RATE_LIMITED,
            ToolErrorCode.OPERATION_TIMEOUT,
            ToolErrorCode.PAYLOAD_TOO_LARGE,
            ToolErrorCode.UPLOAD_SESSION_EXPIRED,
            ToolErrorCode.UPLOAD_SESSION_ABORTED,
            ToolErrorCode.UNSUPPORTED_MEDIA_TYPE,
            ToolErrorCode.UNSUPPORTED_TOOL_OPERATION,
            ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
            ToolErrorCode.TENANT_SCOPE_DENIED,
            ToolErrorCode.INTERNAL_AGENT_ERROR,
        )
    }
})
