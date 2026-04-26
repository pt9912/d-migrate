package dev.dmigrate.server.application.approval

import java.security.MessageDigest

/**
 * Adapters compute the fingerprint of a raw approval token at the wire
 * boundary; the raw token never crosses into application or store
 * code. The fingerprint is stable, deterministic, and stored on
 * [dev.dmigrate.server.core.approval.ApprovalGrant.approvalTokenFingerprint].
 */
object ApprovalTokenFingerprint {

    fun compute(rawToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawToken.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    // TODO(refactoring-sha256Hex.md): replace with shared hex encoder
    // once the consolidation pass lands.
    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX_CHARS[(b.toInt() ushr NIBBLE_SHIFT) and NIBBLE_MASK])
            sb.append(HEX_CHARS[b.toInt() and NIBBLE_MASK])
        }
        return sb.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
    private const val NIBBLE_SHIFT = 4
    private const val NIBBLE_MASK = 0xF
}
