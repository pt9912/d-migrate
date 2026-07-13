package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class PipelineParallelismResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-parallelism-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    // --- Präzedenz CLI > Config > Default ---

    test("no CLI, no config — default 1, not from CLI") {
        val eff = resolveEffectiveParallelism(configPath = null, cliParallel = null)
        eff.degree shouldBe 1
        eff.fromCli shouldBe false
    }

    test("CLI value wins and is marked fromCli") {
        val file = tempConfig("pipeline:\n  parallelism: 4\n")
        val eff = resolveEffectiveParallelism(configPath = file, cliParallel = 7)
        eff.degree shouldBe 7
        eff.fromCli shouldBe true
        eff.sourceLabel shouldContain "--parallel 7"
    }

    test("config fixed value used when CLI omitted (not fromCli)") {
        val file = tempConfig("pipeline:\n  parallelism: 4\n")
        val eff = resolveEffectiveParallelism(configPath = file, cliParallel = null)
        eff.degree shouldBe 4
        eff.fromCli shouldBe false
        eff.sourceLabel shouldContain "pipeline.parallelism: 4"
    }

    // --- auto ---

    test("config auto resolves to min(cores, poolSize) and labels the config origin") {
        val file = tempConfig("pipeline:\n  parallelism: auto\n")
        val eff = resolveEffectiveParallelism(
            configPath = file, cliParallel = null, availableProcessors = 32, maxPoolSize = 10,
        )
        eff.degree shouldBe 10 // capped by pool size, not 32 cores
        eff.fromCli shouldBe false
        eff.sourceLabel shouldContain "pipeline.parallelism: auto (= 10)"
    }

    test("config auto capped by cores when fewer cores than pool") {
        val file = tempConfig("pipeline:\n  parallelism: AUTO\n") // case-insensitive
        val eff = resolveEffectiveParallelism(
            configPath = file, cliParallel = null, availableProcessors = 4, maxPoolSize = 10,
        )
        eff.degree shouldBe 4
    }

    // --- Validierung ---

    test("CLI --parallel < 1 throws IllegalArgumentException (→ Exit 2)") {
        val ex = shouldThrow<IllegalArgumentException> {
            resolveEffectiveParallelism(configPath = null, cliParallel = 0)
        }
        ex.message shouldContain "--parallel"
    }

    test("config parallelism 0 — ConfigResolveException") {
        val file = tempConfig("pipeline:\n  parallelism: 0\n")
        shouldThrow<ConfigResolveException> {
            resolveEffectiveParallelism(configPath = file, cliParallel = null)
        }
    }

    test("config parallelism non-int/non-auto string — ConfigResolveException") {
        val file = tempConfig("pipeline:\n  parallelism: banana\n")
        val ex = shouldThrow<ConfigResolveException> {
            resolveEffectiveParallelism(configPath = file, cliParallel = null)
        }
        ex.message shouldContain "auto"
    }

    test("config parallelism fractional — ConfigResolveException (no silent coercion)") {
        val file = tempConfig("pipeline:\n  parallelism: 2.5\n")
        shouldThrow<ConfigResolveException> {
            resolveEffectiveParallelism(configPath = file, cliParallel = null)
        }
    }
})
