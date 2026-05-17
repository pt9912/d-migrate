package dev.dmigrate.server.application.routine

import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.RoutineKindCapability
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 0.9.7 routine-capability-configurable-source Sub-Slice A pins for
 * [RoutineCapabilityConfigParser]. Negative inputs collapse the whole
 * envelope to [EffectiveRoutineCapability.Invalid] with a distinct
 * `reason` — distinct so Kover branches each Invalid path (plan §8
 * risk note "Kover-Coverage-Gate für Sub-Slice A").
 *
 * Positive inputs exercise CLI-only, YAML-only, the empty-source
 * defaults fallback, and the CLI-overrides-YAML precedence rule
 * (plan §8 risk note: CLI wins per routine kind, no conflict-Invalid).
 */
class RoutineCapabilityConfigParserTest : FunSpec({

    val defaultsValid = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = false),
        procedure = RoutineKindCapability(enabled = false),
    )

    fun parse(
        cli: List<String> = emptyList(),
        yaml: Map<String, Any?>? = null,
        defaults: EffectiveRoutineCapability.Valid = defaultsValid,
    ) = RoutineCapabilityConfigParser.parse(cli, yaml, defaults)

    // ── Negative pins (each Invalid.reason path is distinct) ──────

    test("CLI: 'enabled=yes' is rejected as a non-strict boolean") {
        val result = parse(cli = listOf("function:enabled=yes"))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("invalid 'enabled' value 'yes'")
    }

    test("CLI: unknown key inside a kind block is rejected") {
        val result = parse(cli = listOf("function:enabled=true,foo=bar"))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("unknown key 'foo'")
    }

    test("CLI: unparsable minServerVersion is rejected") {
        val result = parse(cli = listOf("function:enabled=true,minServerVersion=not-a-version"))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("unparsable 'minServerVersion'")
    }

    test("CLI: unknown routine kind is rejected") {
        val result = parse(cli = listOf("trigger:enabled=true"))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("unknown routine kind 'trigger'")
    }

    test("CLI: duplicate --routine-capability for the same kind is rejected") {
        val result = parse(
            cli = listOf("function:enabled=true", "function:enabled=false"),
        )
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("duplicate --routine-capability for kind=function")
    }

    test("CLI: missing ':' separator is rejected as syntax error") {
        val result = parse(cli = listOf("functionenabled=true"))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("invalid --routine-capability syntax")
    }

    test("CLI: kind block without 'enabled' is rejected (symmetric to YAML missing-enabled)") {
        // Pins parseCliPairs's "missing required key 'enabled'" branch
        // for the CLI source so it lives at parity with the YAML
        // missing-enabled rejection.
        val result = parse(cli = listOf("function:minServerVersion=8.0.0"))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("missing required key 'enabled'")
    }

    test("YAML: minServerVersion as a YAML float (8.0 -> Double) is rejected") {
        // Plan §8 risk note: SnakeYAML coerces unquoted `8.0` to Double;
        // operator must quote as "8.0.0" so the parser reaches the
        // canonical major.minor.patch shape.
        val result = parse(
            yaml = mapOf("function" to mapOf("enabled" to true, "minServerVersion" to 8.0)),
        )
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("must be a quoted string")
    }

    test("YAML: unknown top-level kind is rejected") {
        val result = parse(yaml = mapOf("view" to mapOf("enabled" to true)))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("unknown routine kind 'view'")
    }

    test("YAML: enabled as a string is rejected (strict boolean type)") {
        val result = parse(yaml = mapOf("function" to mapOf("enabled" to "true")))
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("must be a boolean")
    }

    // ── Positive pins ─────────────────────────────────────────────

    test("CLI-only with both kinds yields a Valid envelope populated from CLI") {
        val result = parse(
            cli = listOf(
                "function:enabled=true,minServerVersion=8.0.0",
                "procedure:enabled=false",
            ),
        )
        result shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0)),
            procedure = RoutineKindCapability(enabled = false),
        )
    }

    test("YAML-only is parsed and merged on top of the defaults fallback") {
        val result = parse(
            yaml = mapOf(
                "function" to mapOf("enabled" to true, "minServerVersion" to "10.11.6"),
                "procedure" to mapOf("enabled" to false),
            ),
        )
        result shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(10, 11, 6)),
            procedure = RoutineKindCapability(enabled = false),
        )
    }

    test("Empty CLI and absent YAML returns the dialect/server-version defaults") {
        val customDefaults = EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true),
            procedure = RoutineKindCapability(enabled = true),
        )
        parse(defaults = customDefaults) shouldBe customDefaults
    }

    test("CLI wins over YAML for the same routine kind (no conflict-Invalid)") {
        // Plan §8 risk note pin: CLI and YAML both declare `function`,
        // and the parser keeps the CLI value instead of failing.
        val result = parse(
            cli = listOf("function:enabled=true"),
            yaml = mapOf("function" to mapOf("enabled" to false)),
        )
        result shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true),
            procedure = defaultsValid.procedure,
        )
    }

    test("CLI for function plus YAML for procedure merges per-kind from each source") {
        val result = parse(
            cli = listOf("function:enabled=true,minServerVersion=8.0.0"),
            yaml = mapOf("procedure" to mapOf("enabled" to false)),
        )
        result shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0)),
            procedure = RoutineKindCapability(enabled = false),
        )
    }
})
