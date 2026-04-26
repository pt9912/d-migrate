package dev.dmigrate.mcp.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAtLeastOne
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.nio.file.Files
import java.time.Duration

private val ISSUER = URI.create("https://issuer.example/")
private val JWKS = URI.create("https://issuer.example/.well-known/jwks.json")
private val INTROSPECT = URI.create("https://issuer.example/introspect")
private const val AUDIENCE = "mcp.dmigrate.example"

private fun validJwks() = McpServerConfig(
    authMode = AuthMode.JWT_JWKS,
    issuer = ISSUER,
    jwksUrl = JWKS,
    audience = AUDIENCE,
)

private fun validIntrospection() = McpServerConfig(
    authMode = AuthMode.JWT_INTROSPECTION,
    issuer = ISSUER,
    introspectionUrl = INTROSPECT,
    audience = AUDIENCE,
)

private fun validDisabled() = McpServerConfig(
    authMode = AuthMode.DISABLED,
)

class McpServerConfigValidationTest : FunSpec({

    test("default config (JWT_JWKS, no creds) is invalid — fail-closed") {
        val errs = McpServerConfig().validate()
        errs.forAtLeastOne { it shouldContain "issuer" }
        errs.forAtLeastOne { it shouldContain "jwksUrl" }
        errs.forAtLeastOne { it shouldContain "audience" }
    }

    test("DISABLED on loopback bind validates") {
        validDisabled().validate().shouldBeEmpty()
    }

    test("DISABLED on 0.0.0.0 rejects (§4.3)") {
        val errs = validDisabled().copy(bindAddress = "0.0.0.0").validate()
        errs.forAtLeastOne { it shouldContain "loopback" }
    }

    test("DISABLED on :: rejects") {
        val errs = validDisabled().copy(bindAddress = "::").validate()
        errs.forAtLeastOne { it shouldContain "loopback" }
    }

    test("DISABLED with publicBaseUrl rejects") {
        val errs = validDisabled().copy(
            publicBaseUrl = URI.create("https://mcp.example/"),
        ).validate()
        errs.forAtLeastOne { it shouldContain "publicBaseUrl" }
    }

    test("JWT_JWKS without issuer/jwksUrl/audience rejects") {
        val errs = McpServerConfig(authMode = AuthMode.JWT_JWKS).validate()
        errs.forAtLeastOne { it shouldContain "issuer" }
        errs.forAtLeastOne { it shouldContain "jwksUrl" }
        errs.forAtLeastOne { it shouldContain "audience" }
    }

    test("JWT_JWKS with full set validates") {
        validJwks().validate().shouldBeEmpty()
    }

    test("JWT_INTROSPECTION without introspectionUrl rejects") {
        val errs = validIntrospection().copy(introspectionUrl = null).validate()
        errs.forAtLeastOne { it shouldContain "introspectionUrl" }
    }

    test("JWT_INTROSPECTION with full set validates") {
        validIntrospection().validate().shouldBeEmpty()
    }

    test("allowedOrigins with literal '*' rejects (§12.6)") {
        val errs = validJwks().copy(
            allowedOrigins = setOf("*"),
        ).validate()
        errs.forAtLeastOne { it shouldContain "wildcard" }
    }

    test("non-loopback bind with default origins rejects (§12.6)") {
        // Default origins are loopback-only; a public bind requires
        // an explicit origin list.
        val errs = validJwks().copy(bindAddress = "0.0.0.0").validate()
        errs.forAtLeastOne { it shouldContain "explicit allowedOrigins" }
    }

    test("non-loopback bind with explicit origins validates") {
        validJwks().copy(
            bindAddress = "0.0.0.0",
            allowedOrigins = setOf("https://app.example"),
        ).validate().shouldBeEmpty()
    }

    test("algorithmAllowlist containing 'none' rejects (§12.3)") {
        val errs = validJwks().copy(
            algorithmAllowlist = setOf("RS256", "none"),
        ).validate()
        errs.shouldNotBeEmpty()
        errs.forAtLeastOne { it shouldContain "none" }
    }

    test("algorithmAllowlist containing HS256 rejects") {
        val errs = validJwks().copy(
            algorithmAllowlist = setOf("RS256", "HS256"),
        ).validate()
        errs.shouldNotBeEmpty()
        errs.forAtLeastOne { it shouldContain "HS256" }
    }

    test("port outside 0..65535 rejects") {
        val errs = validJwks().copy(port = 70_000).validate()
        errs.forAtLeastOne { it shouldContain "port" }
    }

    test("negative port rejects") {
        val errs = validJwks().copy(port = -1).validate()
        errs.forAtLeastOne { it shouldContain "port" }
    }

    test("negative clockSkew rejects") {
        val errs = validJwks().copy(clockSkew = Duration.ofSeconds(-1)).validate()
        errs.forAtLeastOne { it shouldContain "clockSkew" }
    }

    test("clockSkew over 5min rejects") {
        val errs = validJwks().copy(clockSkew = Duration.ofMinutes(6)).validate()
        errs.forAtLeastOne { it shouldContain "clockSkew" }
    }

    test("publicBaseUrl with non-https scheme rejects (§4.4)") {
        val errs = validJwks().copy(
            bindAddress = "0.0.0.0",
            allowedOrigins = setOf("https://app.example"),
            publicBaseUrl = URI.create("http://mcp.example/"),
        ).validate()
        errs.forAtLeastOne { it shouldContain "https" }
    }

    test("publicBaseUrl with https scheme validates") {
        validJwks().copy(
            bindAddress = "0.0.0.0",
            allowedOrigins = setOf("https://app.example"),
            publicBaseUrl = URI.create("https://mcp.example/"),
        ).validate().shouldBeEmpty()
    }

    test("stdioTokenFile pointing to missing path rejects (§12.10)") {
        val errs = validJwks().copy(
            stdioTokenFile = java.nio.file.Path.of("/tmp/does-not-exist-${System.nanoTime()}"),
        ).validate()
        errs.forAtLeastOne { it shouldContain "stdioTokenFile" }
    }

    test("stdioTokenFile pointing to readable file validates") {
        val tmp = Files.createTempFile("d-migrate-stdio-", ".json")
        try {
            validJwks().copy(stdioTokenFile = tmp).validate().shouldBeEmpty()
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
})
