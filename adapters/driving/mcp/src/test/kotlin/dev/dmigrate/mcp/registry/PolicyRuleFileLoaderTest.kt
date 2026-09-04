package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class PolicyRuleFileLoaderTest : FunSpec({

    fun tempFile(suffix: String, content: String): Path =
        Files.createTempFile("policy-rule-loader-test-", suffix).also { Files.writeString(it, content) }

    test("parses a full YAML file with all three effects") {
        val file = tempFile(
            ".yaml",
            """
            rules:
              - tenantId: acme
                toolName: schema_reverse_start
                callerId: alice
                effect: allow
              - toolName: data_import_start
                effect: challenge
                requiredScopes: [dmigrate:writer]
                reasons: ["writes require approval"]
              - effect: deny
                reasonCode: policy:blocked-by-operator
            """.trimIndent(),
        )
        val rules = loadPolicyRules(file)
        rules shouldHaveSize 3

        rules[0].tenantId shouldBe TenantId("acme")
        rules[0].toolName shouldBe "schema_reverse_start"
        rules[0].callerId shouldBe PrincipalId("alice")
        rules[0].effect shouldBe PolicyEffect.Allow

        rules[1].tenantId shouldBe null
        rules[1].effect shouldBe PolicyEffect.Challenge(
            requiredScopes = setOf("dmigrate:writer"),
            reasons = listOf("writes require approval"),
        )

        rules[2].effect shouldBe PolicyEffect.Deny("policy:blocked-by-operator")
    }

    test("parses JSON files (extension-based mapper selection)") {
        val file = tempFile(
            ".json",
            """{"rules": [{"toolName": "job_cancel", "effect": "allow"}]}""",
        )
        val rules = loadPolicyRules(file)
        rules shouldHaveSize 1
        rules[0].toolName shouldBe "job_cancel"
        rules[0].effect shouldBe PolicyEffect.Allow
    }

    test("omitted optional fields become wildcards") {
        val file = tempFile(".yaml", "rules:\n  - effect: allow\n")
        val rule = loadPolicyRules(file).single()
        rule.tenantId shouldBe null
        rule.toolName shouldBe null
        rule.callerId shouldBe null
    }

    test("unknown effect value throws") {
        val file = tempFile(".yaml", "rules:\n  - effect: maybe\n")
        shouldThrow<IllegalStateException> { loadPolicyRules(file) }
    }

    test("challenge without requiredScopes throws") {
        val file = tempFile(".yaml", "rules:\n  - effect: challenge\n")
        shouldThrow<IllegalStateException> { loadPolicyRules(file) }
    }

    test("deny without reasonCode throws") {
        val file = tempFile(".yaml", "rules:\n  - effect: deny\n")
        shouldThrow<IllegalStateException> { loadPolicyRules(file) }
    }

    test("missing 'rules' array throws") {
        val file = tempFile(".yaml", "notRules: []\n")
        shouldThrow<IllegalStateException> { loadPolicyRules(file) }
    }

    test("nonexistent file throws") {
        shouldThrow<IllegalStateException> { loadPolicyRules(Path.of("/nope/does-not-exist.yaml")) }
    }

    test("rule order is preserved (first match wins downstream)") {
        val file = tempFile(
            ".yaml",
            """
            rules:
              - toolName: t1
                effect: allow
              - toolName: t1
                effect: deny
                reasonCode: policy:never-reached
            """.trimIndent(),
        )
        val rules = loadPolicyRules(file)
        rules.map { it.effect } shouldContainExactly listOf(
            PolicyEffect.Allow,
            PolicyEffect.Deny("policy:never-reached"),
        )
    }
})
