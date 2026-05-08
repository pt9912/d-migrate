package dev.dmigrate.server.application.approval

/**
 * How [ApprovalGrantValidator] should treat the grant's
 * `issuerFingerprint`. The composition root must pick one explicitly so
 * the security default can't be silently flipped to "off".
 */
sealed interface IssuerCheck {

    data object Off : IssuerCheck

    data class AllowList(val trusted: Set<String>) : IssuerCheck
}
