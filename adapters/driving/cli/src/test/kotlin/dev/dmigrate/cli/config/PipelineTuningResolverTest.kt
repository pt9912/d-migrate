package dev.dmigrate.cli.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class PipelineTuningResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-tuning-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    fun resolverFor(file: Path) = PipelineTuningResolver(configPathFromCli = file)

    test("default config path does not exist — returns empty tuning") {
        val tuning = PipelineTuningResolver(
            defaultConfigPath = Path.of("/tmp/does-not-exist-${System.nanoTime()}.yaml"),
            envLookup = { null },
        ).resolve()
        tuning shouldBe PipelineTuning(chunkSize = null, fetchSize = null)
    }

    test("config with pipeline.chunk_size + pipeline.fetch_size — both parsed") {
        val file = tempConfig(
            """
            pipeline:
              chunk_size: 25000
              fetch_size: 500
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe PipelineTuning(chunkSize = 25000, fetchSize = 500)
    }

    test("pipeline block without chunk_size/fetch_size — returns nulls (no silent no-op surprise)") {
        val file = tempConfig(
            """
            pipeline:
              checkpoint:
                enabled: true
            """.trimIndent()
        )
        resolverFor(file).resolve() shouldBe PipelineTuning(chunkSize = null, fetchSize = null)
    }

    test("non-positive chunk_size — throws ConfigResolveException") {
        val file = tempConfig("pipeline:\n  chunk_size: 0\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "chunk_size"
        ex.message shouldContain "> 0"
    }

    test("non-numeric fetch_size — throws ConfigResolveException") {
        val file = tempConfig("pipeline:\n  fetch_size: \"lots\"\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "fetch_size"
        ex.message shouldContain "positive integer"
    }

    // --- resolveEffectivePipelineTuning: Präzedenz CLI > Config > Default ---

    test("CLI value wins over config value") {
        val file = tempConfig("pipeline:\n  chunk_size: 25000\n  fetch_size: 500\n")
        val eff = resolveEffectivePipelineTuning(
            configPath = file, cliChunkSize = 999, cliFetchSize = 111,
        )
        eff.chunkSize shouldBe 999
        eff.fetchSize shouldBe 111
    }

    test("config value used when CLI omitted") {
        val file = tempConfig("pipeline:\n  chunk_size: 25000\n  fetch_size: 500\n")
        val eff = resolveEffectivePipelineTuning(
            configPath = file, cliChunkSize = null, cliFetchSize = null,
        )
        eff.chunkSize shouldBe 25000
        eff.fetchSize shouldBe 500
    }

    test("built-in default chunk size when neither CLI nor config set; fetch stays null") {
        val file = tempConfig("database:\n  host: localhost\n")
        val eff = resolveEffectivePipelineTuning(
            configPath = file, cliChunkSize = null, cliFetchSize = null,
        )
        eff.chunkSize shouldBe 10_000
        eff.fetchSize shouldBe null
    }

    test("CLI chunk size <= 0 — throws IllegalArgumentException (→ Exit 2 in command)") {
        val ex = shouldThrow<IllegalArgumentException> {
            resolveEffectivePipelineTuning(configPath = null, cliChunkSize = 0, cliFetchSize = null)
        }
        ex.message shouldContain "chunk-size"
    }

    test("CLI fetch size <= 0 — throws IllegalArgumentException (→ Exit 2 in command)") {
        val ex = shouldThrow<IllegalArgumentException> {
            resolveEffectivePipelineTuning(configPath = null, cliChunkSize = null, cliFetchSize = -5)
        }
        ex.message shouldContain "fetch-size"
    }

    // --- #4: strenge Ganzzahl (kein stilles Coercen von Float/Overflow) ---

    test("fractional chunk_size is rejected, not silently truncated to 1") {
        val file = tempConfig("pipeline:\n  chunk_size: 1.9\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "chunk_size"
        ex.message shouldContain "positive integer"
    }

    test("chunk_size above Int.MAX is rejected, not wrapped via toInt()") {
        val file = tempConfig("pipeline:\n  chunk_size: 9999999999\n")
        val ex = shouldThrow<ConfigResolveException> { resolverFor(file).resolve() }
        ex.message shouldContain "chunk_size"
    }

    // --- #1a: resolveEffectiveChunkSize (import) darf pipeline.fetch_size NICHT validieren ---

    test("resolveEffectiveChunkSize ignores an invalid pipeline.fetch_size (import path)") {
        val file = tempConfig("pipeline:\n  chunk_size: 25000\n  fetch_size: -3\n")
        // import: fetch_size irrelevant → kein Fehler, chunk_size greift.
        resolveEffectiveChunkSize(configPath = file, cliChunkSize = null) shouldBe 25000
        // contrast: der volle Tuning-Pfad (export/transfer) MUSS an fetch_size scheitern.
        shouldThrow<ConfigResolveException> {
            resolveEffectivePipelineTuning(configPath = file, cliChunkSize = null, cliFetchSize = null)
        }
    }
})
