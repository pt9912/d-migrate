package dev.dmigrate.cli.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

class McpStateRetentionTest : FunSpec({

    test("never disables the sweep") {
        RetentionParser.parse("never") shouldBe RetentionPolicy.Never
        RetentionParser.parse("NEVER") shouldBe RetentionPolicy.Never
    }

    test("0 and 0s map to Immediate") {
        RetentionParser.parse("0") shouldBe RetentionPolicy.Immediate
        RetentionParser.parse("0s") shouldBe RetentionPolicy.Immediate
    }

    test("compact suffix forms parse to a positive Duration") {
        RetentionParser.parse("100ms") shouldBe RetentionPolicy.After(Duration.ofMillis(100))
        RetentionParser.parse("30s") shouldBe RetentionPolicy.After(Duration.ofSeconds(30))
        RetentionParser.parse("15m") shouldBe RetentionPolicy.After(Duration.ofMinutes(15))
        RetentionParser.parse("24h") shouldBe RetentionPolicy.After(Duration.ofHours(24))
        RetentionParser.parse("7d") shouldBe RetentionPolicy.After(Duration.ofDays(7))
    }

    test("ISO-8601 PT… durations parse to a positive Duration") {
        RetentionParser.parse("PT15M") shouldBe RetentionPolicy.After(Duration.ofMinutes(15))
        RetentionParser.parse("P1D") shouldBe RetentionPolicy.After(Duration.ofDays(1))
        RetentionParser.parse("pt2h30m") shouldBe RetentionPolicy.After(Duration.ofMinutes(150))
    }

    test("blank input is rejected") {
        val ex = shouldThrow<StateDirConfigError> { RetentionParser.parse("   ") }
        ex.message!! shouldContain "empty"
    }

    test("zero with a non-`0`-form unit is rejected") {
        val ex = shouldThrow<StateDirConfigError> { RetentionParser.parse("0h") }
        ex.message!! shouldContain "must be positive"
    }

    test("unknown unit is rejected") {
        shouldThrow<StateDirConfigError> { RetentionParser.parse("5y") }
    }

    test("garbage is rejected") {
        val ex = shouldThrow<StateDirConfigError> { RetentionParser.parse("totally-bogus") }
        ex.message!! shouldContain "invalid orphan retention"
    }

    test("invalid PT… is rejected") {
        shouldThrow<StateDirConfigError> { RetentionParser.parse("PTbogus") }
    }

    test("zero ISO-8601 duration is rejected") {
        val ex = shouldThrow<StateDirConfigError> { RetentionParser.parse("PT0S") }
        ex.message!! shouldContain "must be positive"
    }

    test("resolve precedence: CLI > env > default") {
        // CLI wins
        RetentionParser.resolve(
            cliOption = "5m",
            env = { if (it == RetentionParser.ENV_VAR) "1h" else null },
        ) shouldBe RetentionPolicy.After(Duration.ofMinutes(5))

        // env when CLI is null
        RetentionParser.resolve(
            cliOption = null,
            env = { if (it == RetentionParser.ENV_VAR) "1h" else null },
        ) shouldBe RetentionPolicy.After(Duration.ofHours(1))

        // default 24h
        RetentionParser.resolve(
            cliOption = null,
            env = { null },
        ) shouldBe RetentionPolicy.After(Duration.ofHours(24))
    }

    test("blank env value falls through to default") {
        val resolved = RetentionParser.resolve(
            cliOption = null,
            env = { if (it == RetentionParser.ENV_VAR) "   " else null },
        )
        resolved.shouldBeInstanceOf<RetentionPolicy.After>()
            .duration shouldBe Duration.ofHours(24)
    }
})
