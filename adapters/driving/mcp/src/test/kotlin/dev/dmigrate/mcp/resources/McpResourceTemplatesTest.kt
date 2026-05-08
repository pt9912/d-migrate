package dev.dmigrate.mcp.resources

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe

class McpResourceTemplatesTest : FunSpec({

    test("§5.5: 7 templates are published in the documented order") {
        val uris = McpResourceTemplates.ALL.map { it.uriTemplate }
        uris shouldContainInOrder listOf(
            "dmigrate://tenants/{tenantId}/jobs/{jobId}",
            "dmigrate://tenants/{tenantId}/artifacts/{artifactId}",
            "dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}",
            "dmigrate://tenants/{tenantId}/schemas/{schemaId}",
            "dmigrate://tenants/{tenantId}/profiles/{profileId}",
            "dmigrate://tenants/{tenantId}/diffs/{diffId}",
            "dmigrate://tenants/{tenantId}/connections/{connectionId}",
        )
    }

    test("chunk template is included for streaming large artifacts (§6.9 acceptance)") {
        McpResourceTemplates.ALL.any {
            it.uriTemplate == "dmigrate://tenants/{tenantId}/artifacts/{artifactId}/chunks/{chunkId}"
        } shouldBe true
    }

    test("every template advertises application/json mimeType (§5.6)") {
        McpResourceTemplates.ALL.all { it.mimeType == "application/json" } shouldBe true
    }

    test("every template carries a non-blank name and description") {
        McpResourceTemplates.ALL.forEach { tpl ->
            tpl.name.isNotBlank() shouldBe true
            tpl.description!!.isNotBlank() shouldBe true
        }
    }
})
