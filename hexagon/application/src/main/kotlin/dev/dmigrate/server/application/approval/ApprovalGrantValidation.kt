package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.ApprovalGrant

sealed interface ApprovalGrantValidation {

    data class Valid(val grant: ApprovalGrant) : ApprovalGrantValidation

    sealed interface Invalid : ApprovalGrantValidation {

        data object Unknown : Invalid

        data object Expired : Invalid

        data object TenantMismatch : Invalid

        data object CallerMismatch : Invalid

        data object ToolMismatch : Invalid

        data object CorrelationMismatch : Invalid

        data object PayloadMismatch : Invalid

        data class ScopeMismatch(val missing: Set<String>) : Invalid

        data object IssuerMismatch : Invalid
    }
}
