package dev.dmigrate.cli.commands

import dev.dmigrate.core.seed.ColumnRule
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class SeedRulesFileLoaderTest : FunSpec({

    fun tempFile(suffix: String, content: String): Path =
        Files.createTempFile("seed-rules-loader-test-", suffix).also { Files.writeString(it, content) }

    test("parses a full YAML file with all three strategies") {
        val file = tempFile(
            ".yaml",
            """
            rules:
              - table: users
                column: email
                values: ["a@example.com", "b@example.com"]
                weights: [0.7, 0.3]
              - column: age
                range:
                  min: 18
                  max: 65
              - column: handle
                template: "user-{digits:6}"
            """.trimIndent(),
        )
        val rules = loadSeedRules(file)

        val email = rules.resolve("users", "email")!!
        email.table shouldBe "users"
        email.rule shouldBe ColumnRule.Values(listOf("a@example.com", "b@example.com"), listOf(0.7, 0.3))

        val age = rules.resolve("orders", "age")!!
        age.table shouldBe null
        age.rule shouldBe ColumnRule.Range(18.0, 65.0)

        val handle = rules.resolve("orders", "handle")!!
        (handle.rule as ColumnRule.Template).pattern shouldBe "user-{digits:6}"
    }

    test("values without weights defaults to null (equal distribution downstream)") {
        val file = tempFile(".yaml", "rules:\n  - column: status\n    values: [draft, active]\n")
        val rule = loadSeedRules(file).resolve("posts", "status")!!.rule as ColumnRule.Values
        rule.values shouldBe listOf("draft", "active")
        rule.weights shouldBe null
    }

    test("table omitted becomes a wildcard entry") {
        val file = tempFile(".yaml", "rules:\n  - column: status\n    values: [a]\n")
        loadSeedRules(file).resolve("any_table", "status") shouldBe loadSeedRules(file).resolve("status", "status")
    }

    test("empty values array throws") {
        val file = tempFile(".yaml", "rules:\n  - column: status\n    values: []\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("values/weights length mismatch throws") {
        val file = tempFile(".yaml", "rules:\n  - column: status\n    values: [a, b]\n    weights: [1.0]\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("range with min > max throws") {
        val file = tempFile(".yaml", "rules:\n  - column: age\n    range:\n      min: 65\n      max: 18\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("range missing 'max' throws") {
        val file = tempFile(".yaml", "rules:\n  - column: age\n    range:\n      min: 18\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("a rule with no strategy throws") {
        val file = tempFile(".yaml", "rules:\n  - column: age\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("a rule with two strategies at once throws") {
        val file = tempFile(".yaml", "rules:\n  - column: age\n    values: [1]\n    range:\n      min: 1\n      max: 2\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("a rule missing 'column' throws") {
        val file = tempFile(".yaml", "rules:\n  - values: [a]\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("invalid template syntax throws at load time, not at render time (AE-4-Review-Ergaenzung)") {
        val unclosedBrace = tempFile(".yaml", "rules:\n  - column: handle\n    template: \"user-{word\"\n")
        shouldThrow<IllegalStateException> { loadSeedRules(unclosedBrace) }

        val unknownToken = tempFile(".yaml", "rules:\n  - column: handle\n    template: \"{bogus}\"\n")
        shouldThrow<IllegalStateException> { loadSeedRules(unknownToken) }
    }

    test("missing 'rules' array throws") {
        val file = tempFile(".yaml", "notRules: []\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("nonexistent file throws") {
        shouldThrow<IllegalStateException> { loadSeedRules(Path.of("/nope/does-not-exist.yaml")) }
    }

    test("broken YAML syntax throws") {
        val file = tempFile(".yaml", "rules: [this is not: valid: yaml:\n")
        shouldThrow<IllegalStateException> { loadSeedRules(file) }
    }

    test("rule order is preserved (first match wins downstream, AE-2)") {
        val file = tempFile(
            ".yaml",
            """
            rules:
              - table: users
                column: email
                values: [specific@example.com]
              - column: email
                values: [wildcard@example.com]
            """.trimIndent(),
        )
        val rules = loadSeedRules(file)
        (rules.resolve("users", "email")!!.rule as ColumnRule.Values).values shouldBe listOf("specific@example.com")
        (rules.resolve("orders", "email")!!.rule as ColumnRule.Values).values shouldBe listOf("wildcard@example.com")
    }
})
