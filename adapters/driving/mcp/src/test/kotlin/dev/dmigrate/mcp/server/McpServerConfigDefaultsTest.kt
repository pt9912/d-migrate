package dev.dmigrate.mcp.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Pins the §12.12 defaults so a future widening surfaces here as a
 * deliberate change, not a silent drift.
 */
class McpServerConfigDefaultsTest : FunSpec({

    test("default McpServerConfig matches §12.12") {
        val cfg = McpServerConfig()
        cfg.bindAddress shouldBe "127.0.0.1"
        cfg.port shouldBe 0
        cfg.publicBaseUrl shouldBe null
        cfg.allowedOrigins shouldContainExactly setOf(
            "http://localhost:*", "http://127.0.0.1:*",
        )
        cfg.authMode shouldBe AuthMode.JWT_JWKS
        cfg.issuer shouldBe null
        cfg.jwksUrl shouldBe null
        cfg.introspectionUrl shouldBe null
        cfg.audience shouldBe null
        cfg.algorithmAllowlist shouldContainExactly setOf(
            "RS256", "RS384", "RS512", "ES256", "ES384", "ES512",
        )
        cfg.clockSkew shouldBe Duration.ofSeconds(60)
        // §12.5 — MCP session idle TTL default 30 min.
        cfg.sessionIdleTimeout shouldBe Duration.ofMinutes(30)
        cfg.stdioTokenFile shouldBe null
    }

    test("DEFAULT_SCOPE_MAPPING is reused across instances (no rebuild)") {
        // Companion constant must be the same Map instance, not a fresh
        // one allocated per construction.
        val a = McpServerConfig().scopeMapping
        val b = McpServerConfig().scopeMapping
        check(a === b) { "DEFAULT_SCOPE_MAPPING must be reused across constructions" }
    }

    test("DEFAULT_SCOPE_MAPPING covers §12.9 contract") {
        val map = McpServerConfig.DEFAULT_SCOPE_MAPPING
        // capabilities_list is the only Phase B handler (§12.11)
        map["capabilities_list"] shouldBe setOf("dmigrate:read")
        // discovery
        map.keys shouldContainAll setOf(
            "tools/list", "resources/list", "resources/templates/list", "resources/read",
        )
        // job:start scopes
        map["data_export_start"] shouldBe setOf("dmigrate:job:start")
        // upload scopes
        map["artifact_upload_init"] shouldBe setOf("dmigrate:artifact:upload")
        // data-write scopes
        map["data_import_start"] shouldBe setOf("dmigrate:data:write")
        map["data_transfer_start"] shouldBe setOf("dmigrate:data:write")
        // cancel
        map["job_cancel"] shouldBe setOf("dmigrate:job:cancel")
        // ai
        map["testdata_execute"] shouldBe setOf("dmigrate:ai:execute")
        // admin
        map["connections/list"] shouldBe setOf("dmigrate:admin")
    }
})
