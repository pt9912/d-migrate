package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.error.UnsupportedToolOperationException
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = TenantId("acme"),
    effectiveTenantId = TenantId("acme"),
    allowedTenantIds = setOf(TenantId("acme")),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

class PhaseBRegistriesTest : FunSpec({

    test("default registry registers every 0.9.6 tool from the scope mapping") {
        val registry = PhaseBRegistries.toolRegistry()
        // capabilities_list is in
        registry.find("capabilities_list") shouldNotBe null
        // representative tools from each scope class
        registry.find("schema_validate") shouldNotBe null
        registry.find("schema_reverse_start") shouldNotBe null
        registry.find("artifact_upload_init") shouldNotBe null
        registry.find("data_import_start") shouldNotBe null
        registry.find("job_cancel") shouldNotBe null
        registry.find("procedure_transform_plan") shouldNotBe null
    }

    test("MCP-protocol methods are NOT registered as tools") {
        val registry = PhaseBRegistries.toolRegistry()
        registry.names() shouldNotContain "tools/list"
        registry.names() shouldNotContain "resources/list"
        registry.names() shouldNotContain "resources/templates/list"
        registry.names() shouldNotContain "resources/read"
        registry.names() shouldNotContain "connections/list"
    }

    test("capabilities_list has a real handler that does NOT throw UnsupportedToolOperationException") {
        val registry = PhaseBRegistries.toolRegistry()
        val handler = registry.findHandler("capabilities_list")!!
        val outcome = handler.handle(ToolCallContext("capabilities_list", null, PRINCIPAL))
        outcome.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("non-capabilities tools dispatch to UnsupportedToolHandler") {
        val registry = PhaseBRegistries.toolRegistry()
        val handler = registry.findHandler("schema_validate")!!
        shouldThrow<UnsupportedToolOperationException> {
            handler.handle(ToolCallContext("schema_validate", null, PRINCIPAL))
        }
    }

    test("descriptors carry typed JSON-Schema 2020-12 input + output (AP 6.10)") {
        val registry = PhaseBRegistries.toolRegistry()
        val descriptor = registry.find("schema_validate")!!
        descriptor.inputSchema["\$schema"] shouldBe "https://json-schema.org/draft/2020-12/schema"
        descriptor.outputSchema["\$schema"] shouldBe "https://json-schema.org/draft/2020-12/schema"
        // AP 6.10 swapped stub schemas for typed shapes — assert the
        // typed contract surface so a regression to "type:object only"
        // is caught here too.
        descriptor.inputSchema["properties"] shouldNotBe null
        descriptor.inputSchema["required"] shouldNotBe null
    }

    test("requiredScopes mirror the supplied scopeMapping") {
        val registry = PhaseBRegistries.toolRegistry(McpServerConfig.DEFAULT_SCOPE_MAPPING)
        registry.find("schema_validate")!!.requiredScopes shouldBe setOf("dmigrate:read")
        registry.find("schema_reverse_start")!!.requiredScopes shouldBe setOf("dmigrate:job:start")
        registry.find("data_import_start")!!.requiredScopes shouldBe setOf("dmigrate:data:write")
        registry.find("job_cancel")!!.requiredScopes shouldBe setOf("dmigrate:job:cancel")
        registry.find("procedure_transform_plan")!!.requiredScopes shouldBe setOf("dmigrate:ai:execute")
    }

    test("toolRegistry honours a custom scopeMapping subset (no DEFAULT bleed-through)") {
        val custom = mapOf(
            "capabilities_list" to setOf("dmigrate:read"),
            "schema_validate" to setOf("dmigrate:admin"),
        )
        val registry = PhaseBRegistries.toolRegistry(custom)
        registry.names() shouldContain "schema_validate"
        registry.find("schema_validate")!!.requiredScopes shouldBe setOf("dmigrate:admin")
        // job_cancel is NOT in the custom map so it shouldn't be there
        registry.find("job_cancel") shouldBe null
    }

    test("scopeMapping without capabilities_list is rejected at build time (§12.11)") {
        val incomplete = mapOf("schema_validate" to setOf("dmigrate:read"))
        shouldThrow<IllegalStateException> { PhaseBRegistries.toolRegistry(incomplete) }
    }

    test("scopeMapping with an unknown tool name is rejected (no PhaseBToolSchemas entry)") {
        val rogue = mapOf(
            "capabilities_list" to setOf("dmigrate:read"),
            "unregistered_tool" to setOf("dmigrate:read"),
        )
        shouldThrow<IllegalStateException> { PhaseBRegistries.toolRegistry(rogue) }
    }

    test("custom scopeMapping that includes tools/call drops it (§12.16 PROTOCOL_METHODS)") {
        // §12.16 verbindlich: tools/call must NEVER appear in
        // tools/list — even if a custom scope-mapping accidentally
        // gives it a scope. Otherwise a client could try to dispatch
        // tools/call on itself.
        val custom = mapOf(
            "capabilities_list" to setOf("dmigrate:read"),
            "tools/call" to setOf("dmigrate:read"),
        )
        val registry = PhaseBRegistries.toolRegistry(custom)
        registry.find("tools/call") shouldBe null
        registry.find("capabilities_list") shouldNotBe null
    }

    test("resourceRegistry is empty in Phase B") {
        PhaseBRegistries.resourceRegistry().isEmpty() shouldBe true
    }
})
