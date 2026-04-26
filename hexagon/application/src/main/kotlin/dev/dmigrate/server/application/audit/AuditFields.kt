package dev.dmigrate.server.application.audit

/**
 * Mutable builder for fields that are not known at [AuditScope.around]
 * entry. The block populates these as the work progresses; the scope
 * reads them in the `finally`-Pfad when emitting the [AuditEvent].
 * On early failure (before the block sets anything) the audit event
 * records the unset defaults.
 */
class AuditFields {
    var payloadFingerprint: String? = null
    var resourceRefs: List<String> = emptyList()
}
