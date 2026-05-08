package dev.dmigrate.server.application.fingerprint

import java.security.MessageDigest

interface PayloadFingerprintService {

    fun fingerprint(
        scope: FingerprintScope,
        payload: JsonValue.Obj,
        bind: BindContext,
    ): String
}

class DefaultPayloadFingerprintService : PayloadFingerprintService {

    override fun fingerprint(
        scope: FingerprintScope,
        payload: JsonValue.Obj,
        bind: BindContext,
    ): String {
        val normalized = FingerprintNormalization.normalize(scope, payload, bind)
        val canonical = JsonCanonicalizer.canonicalize(normalized)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.toHex()
    }

    // TODO(refactoring-sha256Hex.md): replace with shared hex encoder
    // once the consolidation pass lands; this is the 7th lookup-table
    // copy in the repo.
    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX_CHARS[(b.toInt() ushr NIBBLE_SHIFT) and NIBBLE_MASK])
            sb.append(HEX_CHARS[b.toInt() and NIBBLE_MASK])
        }
        return sb.toString()
    }

    private companion object {
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
        private const val NIBBLE_SHIFT = 4
        private const val NIBBLE_MASK = 0xF
    }
}
