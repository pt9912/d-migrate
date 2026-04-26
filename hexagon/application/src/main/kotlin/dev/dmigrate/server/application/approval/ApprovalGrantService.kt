package dev.dmigrate.server.application.approval

import dev.dmigrate.server.ports.ApprovalGrantStore
import java.time.Instant

interface ApprovalGrantService {

    fun validate(attempt: ApprovalAttempt, now: Instant): ApprovalGrantValidation
}

class DefaultApprovalGrantService(
    private val store: ApprovalGrantStore,
    private val validator: ApprovalGrantValidator,
) : ApprovalGrantService {

    override fun validate(attempt: ApprovalAttempt, now: Instant): ApprovalGrantValidation {
        val grant = store.findByTokenFingerprint(attempt.tenantId, attempt.tokenFingerprint)
            ?: return ApprovalGrantValidation.Invalid.Unknown
        return validator.validate(grant, attempt, now)
    }
}
