package dev.dmigrate.mcp.auth

import java.security.MessageDigest

/**
 * SHA-256-Hex-Fingerprint fuer rohe `DMIGRATE_MCP_STDIO_TOKEN`-Werte
 * per `ImpPlan-0.9.6-B.md` §12.10.
 *
 * Der rohe Tokenwert verlaesst den Aufruf-Stack nie — wir hashen
 * sofort und reichen ausschliesslich den Fingerprint zur
 * `StdioTokenStore.lookup`-Aufloesung weiter. Das deckt sich mit
 * `SecretScrubber.APPROVAL_TOKEN`, das `tok_*`-Praefixe ohnehin im
 * Audit redigiert.
 */
internal object StdioTokenFingerprint {

    /**
     * Liefert lowercase Hex-Repraesentation der SHA-256-Hash des in
     * UTF-8 kodierten [token]. Wirft `IllegalArgumentException` wenn
     * [token] leer ist — ein leerer Tokenwert kann strukturell kein
     * gueltiger Bearer-/stdio-Token sein.
     */
    fun of(token: String): String {
        require(token.isNotEmpty()) { "token must not be empty" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            HEX[(byte.toInt() shr HALF_BYTE_SHIFT) and HEX_MASK].toString() +
                HEX[byte.toInt() and HEX_MASK]
        }
    }

    private const val HEX = "0123456789abcdef"
    private const val HALF_BYTE_SHIFT = 4
    private const val HEX_MASK = 0xF
}
