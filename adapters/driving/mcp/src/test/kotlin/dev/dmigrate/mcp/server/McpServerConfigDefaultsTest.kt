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

    test("Plan §6 G.5 Akzeptanz: alle drei KI-nahen Tools sind mit dmigrate:ai:execute registriert") {
        // Die drei produktiven KI-Tools aus Plan §5.4-5.6 muessen
        // strikt auf `dmigrate:ai:execute` gemappt sein und duerfen
        // weder auf den fail-closed `dmigrate:admin`-Fallback noch
        // auf den read-only `dmigrate:read`-Bereich fallen. Wenn ein
        // zukuenftiger Refactor die Mappings versehentlich entfernt,
        // schlaegt dieser Test an, bevor ein read-only Caller in den
        // KI-Pfad rutscht.
        val map = McpServerConfig.DEFAULT_SCOPE_MAPPING
        val aiExecute = setOf("dmigrate:ai:execute")
        map["procedure_transform_plan"] shouldBe aiExecute
        map["procedure_transform_execute"] shouldBe aiExecute
        map["testdata_plan"] shouldBe aiExecute
        // testdata_execute ist als Phase-G-Carve-out ebenfalls
        // KI-Scope (Slot bleibt registriert; Handler ist
        // UnsupportedToolHandler bis zum 0.9.7-Erweiterungs-AP).
        map["testdata_execute"] shouldBe aiExecute
    }
})
