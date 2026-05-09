package dev.dmigrate.server.application.approval

import dev.dmigrate.core.util.sha256Hex

/**
 * Adapters compute the fingerprint of a raw approval token at the wire
 * boundary; the raw token never crosses into application or store
 * code. The fingerprint is stable, deterministic, and stored on
 * [dev.dmigrate.server.core.approval.ApprovalGrant.approvalTokenFingerprint].
 */
object ApprovalTokenFingerprint {

    fun compute(rawToken: String): String = sha256Hex(rawToken)
}
