package dev.dmigrate.server.application.error

import dev.dmigrate.server.application.quota.RateLimitedDetail
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.quota.QuotaDimension
import java.time.Duration

internal object ApplicationExceptionFixtures {

    data class Case(
        val code: ToolErrorCode,
        val exception: ApplicationException,
        val expectedDetails: List<ToolErrorDetail>,
    )

    val cases: List<Case> = listOf(
        Case(
            ToolErrorCode.AUTH_REQUIRED,
            AuthRequiredException(),
            emptyList(),
        ),
        Case(
            ToolErrorCode.FORBIDDEN_PRINCIPAL,
            ForbiddenPrincipalException(PrincipalId("alice"), reason = "blocked"),
            listOf(
                ToolErrorDetail("principalId", "alice"),
                ToolErrorDetail("reason", "blocked"),
            ),
        ),
        Case(
            ToolErrorCode.POLICY_REQUIRED,
            PolicyRequiredException("data.export.policy"),
            listOf(ToolErrorDetail("policyName", "data.export.policy")),
        ),
        Case(
            ToolErrorCode.POLICY_DENIED,
            PolicyDeniedException("p1", reason = "no scope"),
            listOf(
                ToolErrorDetail("policyName", "p1"),
                ToolErrorDetail("reason", "no scope"),
            ),
        ),
        Case(
            ToolErrorCode.IDEMPOTENCY_KEY_REQUIRED,
            IdempotencyKeyRequiredException(),
            emptyList(),
        ),
        Case(
            ToolErrorCode.IDEMPOTENCY_CONFLICT,
            IdempotencyConflictException("a".repeat(64)),
            listOf(ToolErrorDetail("existingFingerprint", "a".repeat(64))),
        ),
        Case(
            ToolErrorCode.RESOURCE_NOT_FOUND,
            ResourceNotFoundException(
                ServerResourceUri(TenantId("acme"), ResourceKind.JOBS, "job-1"),
            ),
            listOf(ToolErrorDetail("resourceUri", "dmigrate://tenants/acme/jobs/job-1")),
        ),
        Case(
            ToolErrorCode.VALIDATION_ERROR,
            ValidationErrorException(emptyList()),
            emptyList(),
        ),
        Case(
            ToolErrorCode.RATE_LIMITED,
            RateLimitedException(
                RateLimitedDetail(QuotaDimension.UPLOAD_BYTES, current = 950, limit = 1_000),
            ),
            listOf(
                ToolErrorDetail("dimension", "UPLOAD_BYTES"),
                ToolErrorDetail("current", "950"),
                ToolErrorDetail("limit", "1000"),
            ),
        ),
        Case(
            ToolErrorCode.OPERATION_TIMEOUT,
            OperationTimeoutException("data.export", Duration.ofSeconds(30)),
            listOf(
                ToolErrorDetail("operation", "data.export"),
                ToolErrorDetail("budget", "PT30S"),
            ),
        ),
        Case(
            ToolErrorCode.PAYLOAD_TOO_LARGE,
            PayloadTooLargeException(actualBytes = 2048, maxBytes = 1024),
            listOf(
                ToolErrorDetail("actualBytes", "2048"),
                ToolErrorDetail("maxBytes", "1024"),
            ),
        ),
        Case(
            ToolErrorCode.UPLOAD_SESSION_EXPIRED,
            UploadSessionExpiredException("u1"),
            listOf(ToolErrorDetail("sessionId", "u1")),
        ),
        Case(
            ToolErrorCode.UPLOAD_SESSION_ABORTED,
            UploadSessionAbortedException("u1"),
            listOf(ToolErrorDetail("sessionId", "u1")),
        ),
        Case(
            ToolErrorCode.UNSUPPORTED_MEDIA_TYPE,
            UnsupportedMediaTypeException("text/xml", setOf("text/plain", "application/json")),
            listOf(
                ToolErrorDetail("actual", "text/xml"),
                ToolErrorDetail("allowed", "application/json,text/plain"),
            ),
        ),
        Case(
            ToolErrorCode.UNSUPPORTED_TOOL_OPERATION,
            UnsupportedToolOperationException("export", "stream"),
            listOf(
                ToolErrorDetail("toolName", "export"),
                ToolErrorDetail("operation", "stream"),
            ),
        ),
        Case(
            ToolErrorCode.PROMPT_HYGIENE_BLOCKED,
            PromptHygieneBlockedException("redaction-required"),
            listOf(ToolErrorDetail("reason", "redaction-required")),
        ),
        Case(
            ToolErrorCode.TENANT_SCOPE_DENIED,
            TenantScopeDeniedException(TenantId("initech")),
            listOf(ToolErrorDetail("requestedTenant", "initech")),
        ),
        Case(
            ToolErrorCode.INTERNAL_AGENT_ERROR,
            InternalAgentErrorException(),
            emptyList(),
        ),
    )
}
