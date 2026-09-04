package dev.dmigrate.core.seed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SeedRulesTest : FunSpec({

    context("SeedRuleEntry.matches") {
        test("table-specific entry matches only its own table") {
            val entry = SeedRuleEntry("users", "email", ColumnRule.Template("{word}"))
            entry.matches("users", "email") shouldBe true
            entry.matches("orders", "email") shouldBe false
        }

        test("wildcard entry (table = null) matches any table") {
            val entry = SeedRuleEntry(null, "email", ColumnRule.Template("{word}"))
            entry.matches("users", "email") shouldBe true
            entry.matches("orders", "email") shouldBe true
        }

        test("column name must match exactly regardless of table") {
            val entry = SeedRuleEntry(null, "email", ColumnRule.Template("{word}"))
            entry.matches("users", "e_mail") shouldBe false
        }
    }

    context("SeedRuleSet.resolve") {
        test("first matching entry in file order wins (wildcard vs wildcard)") {
            val first = SeedRuleEntry(null, "status", ColumnRule.Values(listOf("a")))
            val second = SeedRuleEntry(null, "status", ColumnRule.Values(listOf("b")))
            val set = SeedRuleSet(listOf(first, second))
            set.resolve("users", "status") shouldBe first
        }

        test("AE-2: a wildcard rule before a table-specific rule for the same column shadows it") {
            val wildcard = SeedRuleEntry(null, "email", ColumnRule.Values(listOf("wildcard@example.com")))
            val specific = SeedRuleEntry("users", "email", ColumnRule.Values(listOf("specific@example.com")))
            val set = SeedRuleSet(listOf(wildcard, specific))
            set.resolve("users", "email") shouldBe wildcard
        }

        test("a table-specific rule listed before a wildcard rule for the same column wins for its table") {
            val specific = SeedRuleEntry("users", "email", ColumnRule.Values(listOf("specific@example.com")))
            val wildcard = SeedRuleEntry(null, "email", ColumnRule.Values(listOf("wildcard@example.com")))
            val set = SeedRuleSet(listOf(specific, wildcard))
            set.resolve("users", "email") shouldBe specific
            set.resolve("orders", "email") shouldBe wildcard
        }

        test("no matching entry resolves to null") {
            val set = SeedRuleSet(listOf(SeedRuleEntry("users", "email", ColumnRule.Values(listOf("x")))))
            set.resolve("orders", "email") shouldBe null
        }
    }

    context("SeedRuleSet.markUsed / unused (AE-7)") {
        test("an entry is reported unused until markUsed is called") {
            val entry = SeedRuleEntry("users", "email", ColumnRule.Values(listOf("x")))
            val set = SeedRuleSet(listOf(entry))
            set.unused() shouldBe listOf(entry)
            set.markUsed(entry)
            set.unused() shouldBe emptyList()
        }

        test("markUsed is idempotent -- repeated calls for the same entry do not change unused()") {
            val entry = SeedRuleEntry("users", "email", ColumnRule.Values(listOf("x")))
            val set = SeedRuleSet(listOf(entry))
            repeat(5) { set.markUsed(entry) }
            set.unused() shouldBe emptyList()
        }

        test("only entries that were never markUsed appear in unused()") {
            val used = SeedRuleEntry("users", "email", ColumnRule.Values(listOf("x")))
            val neverUsed = SeedRuleEntry("orders", "status", ColumnRule.Values(listOf("y")))
            val set = SeedRuleSet(listOf(used, neverUsed))
            set.markUsed(used)
            set.unused() shouldBe listOf(neverUsed)
        }
    }

    context("parseSeedTemplate") {
        test("parses literal text, word, digits:N and uuid tokens") {
            parseSeedTemplate("user-{word}-{digits:3}-{uuid}") shouldBe listOf(
                TemplateSegment.Literal("user-"),
                TemplateSegment.Word,
                TemplateSegment.Literal("-"),
                TemplateSegment.Digits(3),
                TemplateSegment.Literal("-"),
                TemplateSegment.Uuid,
            )
        }

        test("digits:0 is allowed (empty digit run, no special case)") {
            parseSeedTemplate("{digits:0}") shouldBe listOf(TemplateSegment.Digits(0))
        }

        test("pure literal text with no tokens parses to a single Literal segment") {
            parseSeedTemplate("plain-text") shouldBe listOf(TemplateSegment.Literal("plain-text"))
        }

        test("unclosed '{' throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> { parseSeedTemplate("prefix-{word") }
        }

        test("unmatched '}' throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> { parseSeedTemplate("prefix-}suffix") }
        }

        test("unknown token name throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> { parseSeedTemplate("{bogus}") }
        }

        test("negative digits count throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> { parseSeedTemplate("{digits:-1}") }
        }

        test("non-numeric digits count throws IllegalArgumentException") {
            shouldThrow<IllegalArgumentException> { parseSeedTemplate("{digits:abc}") }
        }
    }

    context("ColumnRule.Template.segments") {
        test("valid pattern computes segments lazily without throwing at construction") {
            val rule = ColumnRule.Template("{word}")
            rule.segments shouldBe listOf(TemplateSegment.Word)
        }

        test("invalid pattern throws only when segments is accessed, not at construction") {
            val rule = ColumnRule.Template("{bogus}")
            shouldThrow<IllegalArgumentException> { rule.segments }
        }
    }
})
