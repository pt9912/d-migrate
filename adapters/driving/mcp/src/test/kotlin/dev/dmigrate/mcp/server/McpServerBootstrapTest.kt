package dev.dmigrate.mcp.server

import dev.dmigrate.mcp.auth.StdioPrincipalResolution
import dev.dmigrate.mcp.auth.StdioTokenFingerprint
import dev.dmigrate.mcp.transport.stdio.StdioJsonRpc
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.StdioTokenGrant
import dev.dmigrate.server.ports.StdioTokenStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAtLeastOne
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.time.Instant

class McpServerBootstrapTest : FunSpec({

    test("startHttp on valid loopback config binds an ephemeral port") {
        val outcome = McpServerBootstrap.startHttp(McpServerConfig(authMode = AuthMode.DISABLED))
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            outcome.handle.boundPort shouldBeGreaterThan 0
            // Verify the port actually accepts a connection.
            Socket("127.0.0.1", outcome.handle.boundPort).use { /* ok */ }
        } finally {
            outcome.handle.close()
        }
    }

    test("startHttp on invalid config returns ConfigError") {
        val outcome = McpServerBootstrap.startHttp(
            McpServerConfig(authMode = AuthMode.DISABLED, bindAddress = "0.0.0.0"),
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.ConfigError>()
        outcome.errors.forAtLeastOne { it shouldContain "loopback" }
    }

    test("startHttp ConfigError aggregates multiple violations") {
        val outcome = McpServerBootstrap.startHttp(McpServerConfig())
        outcome.shouldBeInstanceOf<McpStartOutcome.ConfigError>()
        // default JWT_JWKS without issuer/jwks/audience produces 3 errors
        outcome.errors.size shouldBe 3
    }

    test("after stop a fresh start succeeds") {
        // Avoid pinning the port (TIME_WAIT) — we only need to verify
        // the lifecycle is reusable.
        val first = McpServerBootstrap.startHttp(McpServerConfig(authMode = AuthMode.DISABLED))
        first.shouldBeInstanceOf<McpStartOutcome.Started>()
        first.handle.stop()
        val second = McpServerBootstrap.startHttp(McpServerConfig(authMode = AuthMode.DISABLED))
        second.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            second.handle.boundPort shouldBeGreaterThan 0
        } finally {
            second.handle.close()
        }
    }

    test("stop is idempotent") {
        val outcome = McpServerBootstrap.startHttp(McpServerConfig(authMode = AuthMode.DISABLED))
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        outcome.handle.stop()
        outcome.handle.stop() // second stop must not throw
    }

    test("startStdio on valid config returns Started with boundPort=0") {
        val outcome = McpServerBootstrap.startStdio(
            McpServerConfig(authMode = AuthMode.DISABLED),
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            outcome.handle.boundPort shouldBe 0
        } finally {
            outcome.handle.close()
        }
    }

    test("startStdio on invalid stdio-only config returns ConfigError") {
        // §12.15: stdio ignores authMode entirely, so a default-config
        // (authMode=JWT_JWKS) must NOT fail stdio validation. We trigger
        // a real stdio-relevant violation instead — an unreadable
        // stdioTokenFile.
        val nonExistent = java.nio.file.Paths.get("/no/such/path-${System.nanoTime()}.json")
        val outcome = McpServerBootstrap.startStdio(
            McpServerConfig(stdioTokenFile = nonExistent),
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.ConfigError>()
    }

    test("startStdio with default-config (JWT_JWKS) succeeds — §12.15 ignores authMode") {
        // Counterpart to the above: the default McpServerConfig() is
        // JWT_JWKS-shaped (no issuer/jwks/audience), but that's
        // HTTP-only state. stdio MUST start cleanly.
        val outcome = McpServerBootstrap.startStdio(
            McpServerConfig(),
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        outcome.handle.close()
    }

    test("startStdio without env yields AuthRequired in the bound resolution") {
        val outcome = McpServerBootstrap.startStdio(
            McpServerConfig(authMode = AuthMode.DISABLED),
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
            tokenSupplier = { null },
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            // The handle exposes a StdioJsonRpc which carries the bound
            // resolution; we reach in via a known wrapper. Failing here
            // would mean the bootstrap dropped the resolution silently.
            val rpc = handleRpc(outcome.handle)
            val resolution = rpc.principalResolution
            resolution.shouldBeInstanceOf<StdioPrincipalResolution.AuthRequired>()
            resolution.reason shouldBe "DMIGRATE_MCP_STDIO_TOKEN not set"
        } finally {
            outcome.handle.close()
        }
    }

    test("startStdio with valid token + override store yields Resolved principal") {
        val token = "tok_alice"
        val fp = StdioTokenFingerprint.of(token)
        val store = object : StdioTokenStore {
            override fun lookup(tokenFingerprint: String) =
                if (tokenFingerprint == fp) {
                    StdioTokenGrant(
                        principalId = PrincipalId("alice"),
                        tenantId = TenantId("acme"),
                        scopes = setOf("dmigrate:read"),
                        isAdmin = false,
                        auditSubject = "alice@acme",
                        expiresAt = Instant.now().plusSeconds(3600),
                    )
                } else {
                    null
                }
        }
        val outcome = McpServerBootstrap.startStdio(
            McpServerConfig(authMode = AuthMode.DISABLED),
            input = ByteArrayInputStream(ByteArray(0)),
            output = ByteArrayOutputStream(),
            tokenStoreOverride = store,
            tokenSupplier = { token },
        )
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            val rpc = handleRpc(outcome.handle)
            val resolution = rpc.principalResolution
            resolution.shouldBeInstanceOf<StdioPrincipalResolution.Resolved>()
            resolution.principal.principalId shouldBe PrincipalId("alice")
        } finally {
            outcome.handle.close()
        }
    }
})

/**
 * Reflection helper — `StdioHandle` is private to McpServerBootstrap.kt.
 * Tests need at the bound `StdioJsonRpc` to assert the resolution; doing
 * this via reflection avoids leaking the private wrapper into the public
 * surface of the bootstrap module.
 */
private fun handleRpc(handle: McpServerHandle): StdioJsonRpc {
    val cls = handle.javaClass
    val field = cls.getDeclaredField("rpc")
    field.isAccessible = true
    return field.get(handle) as StdioJsonRpc
}
