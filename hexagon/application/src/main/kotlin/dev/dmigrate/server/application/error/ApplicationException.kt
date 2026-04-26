package dev.dmigrate.server.application.error

import dev.dmigrate.server.application.quota.RateLimitedDetail
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.error.ToolErrorDetail
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Duration

/**
 * Sealed root for application-layer errors. Each [ToolErrorCode] has
 * exactly one subtype; the bidirectional invariant is pinned by
 * `AppExceptionHierarchyTest`.
 *
 * `cause` propagates internally (stack tracing) but is never
 * serialized into [ToolErrorDetail] — that would risk leaking PII or
 * implementation details across the trust boundary.
 */
sealed class ApplicationException(
    val code: ToolErrorCode,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    open fun details(): List<ToolErrorDetail> = emptyList()
}

class AuthRequiredException :
    ApplicationException(ToolErrorCode.AUTH_REQUIRED, "Authentication required")

class ForbiddenPrincipalException(
    val principalId: PrincipalId,
    val reason: String? = null,
) : ApplicationException(ToolErrorCode.FORBIDDEN_PRINCIPAL, "Principal forbidden") {
    override fun details(): List<ToolErrorDetail> = buildList {
        add(ToolErrorDetail("principalId", principalId.value))
        if (reason != null) add(ToolErrorDetail("reason", reason))
    }
}

class PolicyRequiredException(val policyName: String) :
    ApplicationException(ToolErrorCode.POLICY_REQUIRED, "Policy approval required") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("policyName", policyName))
}

class PolicyDeniedException(
    val policyName: String,
    val reason: String? = null,
) : ApplicationException(ToolErrorCode.POLICY_DENIED, "Policy denied") {
    override fun details(): List<ToolErrorDetail> = buildList {
        add(ToolErrorDetail("policyName", policyName))
        if (reason != null) add(ToolErrorDetail("reason", reason))
    }
}

class IdempotencyKeyRequiredException :
    ApplicationException(ToolErrorCode.IDEMPOTENCY_KEY_REQUIRED, "Idempotency key required")

class IdempotencyConflictException(val existingFingerprint: String) :
    ApplicationException(ToolErrorCode.IDEMPOTENCY_CONFLICT, "Idempotency conflict") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("existingFingerprint", existingFingerprint))
}

class ResourceNotFoundException(val resourceUri: ServerResourceUri) :
    ApplicationException(ToolErrorCode.RESOURCE_NOT_FOUND, "Resource not found") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("resourceUri", resourceUri.render()))
}

class ValidationErrorException(val violations: List<ValidationViolation>) :
    ApplicationException(
        ToolErrorCode.VALIDATION_ERROR,
        "Validation failed: ${violations.size} violation(s)",
    ) {
    override fun details(): List<ToolErrorDetail> =
        violations.map { ToolErrorDetail(it.field, it.reason) }
}

class RateLimitedException(val detail: RateLimitedDetail) :
    ApplicationException(ToolErrorCode.RATE_LIMITED, "Rate limit exceeded") {
    override fun details(): List<ToolErrorDetail> = listOf(
        ToolErrorDetail("dimension", detail.dimension.name),
        ToolErrorDetail("current", detail.current.toString()),
        ToolErrorDetail("limit", detail.limit.toString()),
    )
}

class OperationTimeoutException(
    val operation: String,
    val budget: Duration,
) : ApplicationException(ToolErrorCode.OPERATION_TIMEOUT, "Operation timed out") {
    override fun details(): List<ToolErrorDetail> = listOf(
        ToolErrorDetail("operation", operation),
        ToolErrorDetail("budget", budget.toString()),
    )
}

class PayloadTooLargeException(
    val actualBytes: Long,
    val maxBytes: Long,
) : ApplicationException(ToolErrorCode.PAYLOAD_TOO_LARGE, "Payload too large") {
    override fun details(): List<ToolErrorDetail> = listOf(
        ToolErrorDetail("actualBytes", actualBytes.toString()),
        ToolErrorDetail("maxBytes", maxBytes.toString()),
    )
}

class UploadSessionExpiredException(val sessionId: String) :
    ApplicationException(ToolErrorCode.UPLOAD_SESSION_EXPIRED, "Upload session expired") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("sessionId", sessionId))
}

class UploadSessionAbortedException(val sessionId: String) :
    ApplicationException(ToolErrorCode.UPLOAD_SESSION_ABORTED, "Upload session aborted") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("sessionId", sessionId))
}

class UnsupportedMediaTypeException(
    val actual: String,
    val allowed: Set<String>,
) : ApplicationException(ToolErrorCode.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type") {
    override fun details(): List<ToolErrorDetail> = listOf(
        ToolErrorDetail("actual", actual),
        ToolErrorDetail("allowed", allowed.sorted().joinToString(",")),
    )
}

class UnsupportedToolOperationException(
    val toolName: String,
    val operation: String,
) : ApplicationException(ToolErrorCode.UNSUPPORTED_TOOL_OPERATION, "Unsupported tool operation") {
    override fun details(): List<ToolErrorDetail> = listOf(
        ToolErrorDetail("toolName", toolName),
        ToolErrorDetail("operation", operation),
    )
}

class PromptHygieneBlockedException(val reason: String) :
    ApplicationException(ToolErrorCode.PROMPT_HYGIENE_BLOCKED, "Prompt hygiene blocked") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("reason", reason))
}

class TenantScopeDeniedException(val requestedTenant: TenantId) :
    ApplicationException(ToolErrorCode.TENANT_SCOPE_DENIED, "Tenant scope denied") {
    override fun details(): List<ToolErrorDetail> =
        listOf(ToolErrorDetail("requestedTenant", requestedTenant.value))
}

class InternalAgentErrorException(cause: Throwable? = null) :
    ApplicationException(ToolErrorCode.INTERNAL_AGENT_ERROR, "Internal agent error", cause)
