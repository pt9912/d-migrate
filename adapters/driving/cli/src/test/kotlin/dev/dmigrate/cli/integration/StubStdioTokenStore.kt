package dev.dmigrate.cli.integration

import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.StdioTokenGrant
import dev.dmigrate.server.ports.StdioTokenStore
import java.security.MessageDigest
import java.time.Instant

/**
 * AP 6.24: deterministic [StdioTokenStore] for the integration
 * harness — maps a single known token to a fixed [StdioTokenGrant].
 * Production wiring uses [dev.dmigrate.mcp.auth.FileStdioTokenStore]
 * (file-backed); the integration suite needs the same lookup contract
 * without writing a token file to disk.
 */
internal class StubStdioTokenStore private constructor(
    private val fingerprintToGrant: Map<String, StdioTokenGrant>,
) : StdioTokenStore {

    override fun lookup(tokenFingerprint: String): StdioTokenGrant? =
        fingerprintToGrant[tokenFingerprint]

    companion object {
        fun forPrincipal(principal: PrincipalContext, rawToken: String): StubStdioTokenStore {
            val grant = StdioTokenGrant(
                principalId = principal.principalId,
                tenantId = principal.homeTenantId,
                scopes = principal.scopes,
                isAdmin = principal.isAdmin,
                auditSubject = principal.auditSubject,
                expiresAt = Instant.MAX,
            )
            return StubStdioTokenStore(mapOf(sha256Hex(rawToken) to grant))
        }

        private fun sha256Hex(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
