package dev.dmigrate.cli.integration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * `McpScopeEnforcementMatrixTest`-Fixture: baut eine temporäre
 * `stdio-tokens.yaml` mit zwei Prinzipalen für den echten
 * `mcp serve --stdio-token-file`-Pfad (`FileStdioTokenStore.kt`,
 * `StdioPrincipalResolver.kt`) — NICHT über `StubStdioTokenStore`/
 * `IntegrationFixtures`, die ausschließlich am In-Process-Harness
 * (`StdioHarness.kt:253`) hängen und vom Real-Subprozess nicht erreichbar
 * sind (`mcp-real-e2e-scope-matrix.md` AE-A4, Review-Korrektur).
 *
 * Voll-Scope-Prinzipal trägt die Vereinigung aller sieben
 * `McpServerConfig.DEFAULT_SCOPE_MAPPING`-Scopes, bewusst `isAdmin = false`
 * — der Admin-Bypass in `ScopeChecker` würde jede Scope-spezifische
 * Verdrahtung umgehen, statt sie zu beweisen.
 */
internal object ScopeMatrixTokenFile {

    val ALL_SCOPES: Set<String> = setOf(
        "dmigrate:read",
        "dmigrate:job:start",
        "dmigrate:job:cancel",
        "dmigrate:data:write",
        "dmigrate:artifact:upload",
        "dmigrate:ai:execute",
        "dmigrate:admin",
    )

    data class Tokens(val fullScopeToken: String, val noScopeToken: String, val path: Path)

    /** Schreibt `stdio-tokens.yaml` unter [dir] und liefert die zwei rohen Token zurück. */
    fun write(dir: Path): Tokens {
        val fullScopeToken = "tok_full_${UUID.randomUUID()}"
        val noScopeToken = "tok_none_${UUID.randomUUID()}"
        val expiresAt = Instant.now().plusSeconds(EXPIRY_SECONDS)
        val yaml = buildString {
            appendLine("tokens:")
            appendEntry(fingerprint(fullScopeToken), "scope-matrix-full", ALL_SCOPES, expiresAt)
            appendEntry(fingerprint(noScopeToken), "scope-matrix-none", emptySet(), expiresAt)
        }
        val path = dir.resolve("stdio-tokens.yaml")
        Files.writeString(path, yaml, StandardCharsets.UTF_8)
        return Tokens(fullScopeToken, noScopeToken, path)
    }

    private fun StringBuilder.appendEntry(
        fingerprint: String,
        principalId: String,
        scopes: Set<String>,
        expiresAt: Instant,
    ) {
        appendLine("  - fingerprint: \"$fingerprint\"")
        appendLine("    principalId: \"$principalId\"")
        appendLine("    tenantId: \"scope-matrix\"")
        appendLine("    scopes: [${scopes.joinToString(", ") { "\"$it\"" }}]")
        appendLine("    isAdmin: false")
        appendLine("    auditSubject: \"$principalId@scope-matrix\"")
        appendLine("    expiresAt: \"$expiresAt\"")
    }

    /** Gleiche Berechnung wie `StdioTokenFingerprint.of` (dort `internal`, modulübergreifend nicht erreichbar). */
    private fun fingerprint(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private const val EXPIRY_SECONDS = 3_600L
}
