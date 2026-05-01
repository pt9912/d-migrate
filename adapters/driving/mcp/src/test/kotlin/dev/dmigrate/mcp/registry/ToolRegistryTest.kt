package dev.dmigrate.mcp.registry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private fun stubDescriptor(name: String) = ToolDescriptor(
    name = name,
    title = name,
    description = "test",
    requiredScopes = setOf("dmigrate:read"),
    inputSchema = mapOf("type" to "object"),
    outputSchema = mapOf("type" to "object"),
)

class ToolRegistryTest : FunSpec({

    test("registered descriptor is retrievable by name") {
        val handler = ToolHandler { ToolCallOutcome.Success(emptyList()) }
        val registry = ToolRegistry.builder()
            .register(stubDescriptor("foo"), handler)
            .build()
        registry.find("foo")?.name shouldBe "foo"
        registry.findHandler("foo") shouldBe handler
    }

    test("unknown name returns null for both descriptor and handler") {
        val registry = ToolRegistry.builder().build()
        registry.find("nope") shouldBe null
        registry.findHandler("nope") shouldBe null
    }

    test("all() preserves registration order") {
        val handler = ToolHandler { ToolCallOutcome.Success(emptyList()) }
        val registry = ToolRegistry.builder()
            .register(stubDescriptor("a"), handler)
            .register(stubDescriptor("b"), handler)
            .register(stubDescriptor("c"), handler)
            .build()
        registry.all().map { it.name } shouldContainExactly listOf("a", "b", "c")
        registry.names() shouldContainExactly listOf("a", "b", "c")
    }

    test("duplicate registration is rejected") {
        val handler = ToolHandler { ToolCallOutcome.Success(emptyList()) }
        val builder = ToolRegistry.builder().register(stubDescriptor("x"), handler)
        shouldThrow<IllegalArgumentException> {
            builder.register(stubDescriptor("x"), handler)
        }
    }
})
