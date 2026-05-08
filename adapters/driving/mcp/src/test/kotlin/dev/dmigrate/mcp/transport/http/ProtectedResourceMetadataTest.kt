package dev.dmigrate.mcp.transport.http

import com.fasterxml.jackson.databind.ObjectMapper
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.net.URI

private val SCOPES_ORDER = listOf(
    "dmigrate:read",
    "dmigrate:job:start",
    "dmigrate:artifact:upload",
    "dmigrate:data:write",
    "dmigrate:job:cancel",
    "dmigrate:ai:execute",
    "dmigrate:admin",
)

class ProtectedResourceMetadataTest : FunSpec({

    val mapper = ObjectMapper()

    test("JWT_JWKS shape — golden field set + order") {
        val cfg = McpServerConfig(
            authMode = AuthMode.JWT_JWKS,
            issuer = URI.create("https://issuer.example/"),
            jwksUrl = URI.create("https://issuer.example/jwks"),
            audience = "mcp.example",
        )
        val json = ProtectedResourceMetadata.render(cfg, "https://mcp.example/mcp")
        // Field order is part of the contract — verify by raw substring sequence.
        val resourceIdx = json.indexOf("\"resource\"")
        val authIdx = json.indexOf("\"authorization_servers\"")
        val scopesIdx = json.indexOf("\"scopes_supported\"")
        val bearerIdx = json.indexOf("\"bearer_methods_supported\"")
        check(resourceIdx < authIdx) { "resource must come before authorization_servers" }
        check(authIdx < scopesIdx) { "authorization_servers must come before scopes_supported" }
        check(scopesIdx < bearerIdx) { "scopes_supported must come before bearer_methods_supported" }

        // Parse and verify content too.
        val tree = mapper.readTree(json)
        tree["resource"].asText() shouldBe "https://mcp.example/mcp"
        tree["authorization_servers"].map { it.asText() } shouldContainExactly listOf("https://issuer.example/")
        tree["scopes_supported"].map { it.asText() } shouldContainExactly SCOPES_ORDER
        tree["bearer_methods_supported"].map { it.asText() } shouldContainExactly listOf("header")
        json shouldNotContain "x-dmigrate-auth-mode"
    }

    test("DISABLED variant has empty authorization_servers and the demo flag") {
        val cfg = McpServerConfig(authMode = AuthMode.DISABLED)
        val json = ProtectedResourceMetadata.render(cfg, "http://127.0.0.1:8080/mcp")
        val tree = mapper.readTree(json)
        tree["authorization_servers"].size() shouldBe 0
        tree["x-dmigrate-auth-mode"].asText() shouldBe "disabled"
        // Scopes still listed (clients may inspect even in demo mode).
        tree["scopes_supported"].map { it.asText() } shouldContainExactly SCOPES_ORDER
    }

    test("escapes JSON-special characters in resource URI") {
        val cfg = McpServerConfig(authMode = AuthMode.DISABLED)
        val json = ProtectedResourceMetadata.render(cfg, "http://localhost:8080/path\"with\"quotes")
        json shouldContain "\\\"with\\\"quotes"
        // Must still be parseable.
        mapper.readTree(json)
    }
})
