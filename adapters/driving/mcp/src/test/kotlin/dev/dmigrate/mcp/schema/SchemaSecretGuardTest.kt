package dev.dmigrate.mcp.schema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

class SchemaSecretGuardTest : FunSpec({

    test("clean schema has no leaks") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "tenantId" to mapOf("type" to "string"),
                "jobId" to mapOf("type" to "string"),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }

    test("top-level password property is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("password" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.password"
    }

    test("nested object's apiKey property is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "connection" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("apiKey" to mapOf("type" to "string")),
                ),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks shouldContain "properties.connection.properties.apiKey"
    }

    test("array items' password property is detected") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "connections" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf("password" to mapOf("type" to "string")),
                    ),
                ),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks.any { it.contains("password") } shouldBe true
    }

    test("oneOf-branch leaks are caught") {
        val schema = mapOf(
            "oneOf" to listOf(
                mapOf(
                    "type" to "object",
                    "properties" to mapOf("token" to mapOf("type" to "string")),
                ),
                mapOf("type" to "string"),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema).any { it.contains("token") } shouldBe true
    }

    test("\$defs branch leaks are caught") {
        val schema = mapOf(
            "type" to "object",
            "\$defs" to mapOf(
                "Auth" to mapOf(
                    "type" to "object",
                    "properties" to mapOf("jdbcUrl" to mapOf("type" to "string")),
                ),
            ),
        )
        SchemaSecretGuard.findSecretLeaks(schema).any { it.contains("jdbcUrl") } shouldBe true
    }

    test("case-insensitive match for forbidden names") {
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "PASSWORD" to mapOf("type" to "string"),
                "Api_Key" to mapOf("type" to "string"),
            ),
        )
        val leaks = SchemaSecretGuard.findSecretLeaks(schema)
        leaks.any { it.contains("PASSWORD") } shouldBe true
        leaks.any { it.contains("Api_Key") } shouldBe true
    }

    test("forbidden-property names spelled like camelCase are detected") {
        // credentialRef is on the list (lowercase normalised to "credentialref")
        val schema = mapOf(
            "type" to "object",
            "properties" to mapOf("credentialRef" to mapOf("type" to "string")),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldContain "properties.credentialRef"
    }

    test("non-properties top-level keys are not treated as payload names") {
        // 'type', 'required', 'description', 'title' are JSON-Schema
        // keywords, not payload field names — the guard must not raise
        // alarms on them even if they accidentally match a forbidden
        // word later (none currently do, but the test pins the
        // recursion semantics).
        val schema = mapOf(
            "type" to "object",
            "title" to "secret",
            "description" to "contains the word password in prose",
            "required" to listOf("foo"),
        )
        SchemaSecretGuard.findSecretLeaks(schema) shouldBe emptyList()
    }
})
