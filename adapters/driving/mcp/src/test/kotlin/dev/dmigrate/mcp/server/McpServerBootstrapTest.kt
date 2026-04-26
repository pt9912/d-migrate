package dev.dmigrate.mcp.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAtLeastOne
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.Socket

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
        val outcome = McpServerBootstrap.startStdio(McpServerConfig(authMode = AuthMode.DISABLED))
        outcome.shouldBeInstanceOf<McpStartOutcome.Started>()
        try {
            outcome.handle.boundPort shouldBe 0
        } finally {
            outcome.handle.close()
        }
    }

    test("startStdio on invalid config returns ConfigError") {
        val outcome = McpServerBootstrap.startStdio(McpServerConfig())
        outcome.shouldBeInstanceOf<McpStartOutcome.ConfigError>()
    }
})
