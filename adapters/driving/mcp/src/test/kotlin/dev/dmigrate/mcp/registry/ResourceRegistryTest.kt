package dev.dmigrate.mcp.registry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ResourceRegistryTest : FunSpec({

    test("empty() registry has no resources or templates") {
        val sut = ResourceRegistry.empty()
        sut.resources() shouldBe emptyList()
        sut.templates() shouldBe emptyList()
        sut.isEmpty() shouldBe true
    }

    test("builder accumulates resources and templates in registration order") {
        val resourceA = ResourceDescriptor(
            uri = "dmigrate://tenants/acme/jobs/1",
            name = "Job 1",
            mimeType = "application/json",
            requiredScopes = setOf("dmigrate:read"),
        )
        val templateA = ResourceTemplateDescriptor(
            uriTemplate = "dmigrate://tenants/{tenantId}/jobs/{jobId}",
            name = "Job",
            mimeType = "application/json",
            requiredScopes = setOf("dmigrate:read"),
        )
        val sut = ResourceRegistry.builder()
            .register(resourceA)
            .registerTemplate(templateA)
            .build()
        sut.resources() shouldBe listOf(resourceA)
        sut.templates() shouldBe listOf(templateA)
        sut.isEmpty() shouldBe false
    }
})
