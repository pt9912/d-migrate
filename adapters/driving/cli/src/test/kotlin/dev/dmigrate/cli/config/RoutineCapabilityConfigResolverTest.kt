package dev.dmigrate.cli.config

import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.RoutineKindCapability
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class RoutineCapabilityConfigResolverTest : FunSpec({

    val defaults = EffectiveRoutineCapability.Valid(
        function = RoutineKindCapability(enabled = false),
        procedure = RoutineKindCapability(enabled = false),
    )

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-routine-cap-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    // ── Precedence + defaults pins ─────────────────────────────────

    test("no CLI flag + no YAML file (DEFAULT source) returns the supplied defaults envelope") {
        val missing = Path.of("/tmp/dmigrate-routine-cap-missing.yaml")
        Files.deleteIfExists(missing)
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            envLookup = { null },
            defaultConfigPath = missing,
        )
        resolver.resolve(defaults) shouldBe defaults
    }

    test("YAML file present but missing 'routineCapability' section returns the defaults envelope") {
        val cfg = tempConfig(
            """
            i18n:
              default_locale: en_US
            """.trimIndent(),
        )
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        resolver.resolve(defaults) shouldBe defaults
    }

    test("YAML-only configuration overrides defaults per routine kind") {
        val cfg = tempConfig(
            """
            routineCapability:
              function:
                enabled: true
                minServerVersion: "8.0.0"
              procedure:
                enabled: true
            """.trimIndent(),
        )
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        resolver.resolve(defaults) shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true, minServerVersion = MysqlServerVersion(8, 0, 0)),
            procedure = RoutineKindCapability(enabled = true),
        )
    }

    test("CLI wins over YAML for the same routine kind (plan §8 precedence pin)") {
        val cfg = tempConfig(
            """
            routineCapability:
              function:
                enabled: false
              procedure:
                enabled: false
            """.trimIndent(),
        )
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = listOf("function:enabled=true"),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        resolver.resolve(defaults) shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(enabled = true),
            procedure = RoutineKindCapability(enabled = false),
        )
    }

    test("CLI for function plus YAML for procedure merges from each source") {
        val cfg = tempConfig(
            """
            routineCapability:
              procedure:
                enabled: false
            """.trimIndent(),
        )
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = listOf("function:enabled=true,minServerVersion=10.11.6"),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        resolver.resolve(defaults) shouldBe EffectiveRoutineCapability.Valid(
            function = RoutineKindCapability(
                enabled = true,
                minServerVersion = MysqlServerVersion(10, 11, 6),
            ),
            procedure = RoutineKindCapability(enabled = false),
        )
    }

    // ── Failure paths ─────────────────────────────────────────────

    test("missing config file given via --config raises ConfigResolveException") {
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            configPathFromCli = Path.of("/nope-routine-cap.yaml"),
            envLookup = { null },
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve(defaults) }
        ex.message!! shouldContain "Config file not found"
    }

    test("missing config file given via D_MIGRATE_CONFIG raises ConfigResolveException") {
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            envLookup = { name -> if (name == "D_MIGRATE_CONFIG") "/nope-routine-cap-env.yaml" else null },
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve(defaults) }
        ex.message!! shouldContain "D_MIGRATE_CONFIG points to non-existent file"
    }

    test("broken YAML raises ConfigResolveException with a clear message") {
        val cfg = tempConfig("routineCapability:\n  function: {broken\n")
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve(defaults) }
        ex.message!! shouldContain "Failed to parse"
    }

    test("routineCapability that is not a mapping raises ConfigResolveException") {
        val cfg = tempConfig("routineCapability: true\n")
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = emptyList(),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        val ex = shouldThrow<ConfigResolveException> { resolver.resolve(defaults) }
        ex.message!! shouldContain "routineCapability"
        ex.message!! shouldContain "must be a mapping"
    }

    test("YAML structural failure short-circuits before CLI is parsed") {
        // loadYamlSection() runs first; a malformed routineCapability:
        // section throws even when the CLI input alone would be Valid.
        // Pin the load-order so a future "lazy YAML" refactor doesn't
        // silently swap precedence with the CLI parse path.
        val cfg = tempConfig("routineCapability: true\n")
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = listOf("function:enabled=true"),
            configPathFromCli = cfg,
            envLookup = { null },
        )
        shouldThrow<ConfigResolveException> { resolver.resolve(defaults) }
    }

    test("invalid CLI flag is surfaced as EffectiveRoutineCapability.Invalid (parser delegation)") {
        val resolver = RoutineCapabilityConfigResolver(
            cliFlagValues = listOf("function:enabled=yes"),
            envLookup = { null },
            defaultConfigPath = Path.of("/tmp/dmigrate-routine-cap-no-cfg.yaml"),
        )
        val result = resolver.resolve(defaults)
        result.shouldBeInstanceOf<EffectiveRoutineCapability.Invalid>()
        result.reason.shouldContain("invalid 'enabled' value 'yes'")
    }
})
