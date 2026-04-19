package dev.dmigrate.cli.config

import dev.dmigrate.streaming.CheckpointConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class PipelineCheckpointResolverTest : FunSpec({

    fun tempConfig(content: String): Path {
        val file = Files.createTempFile("dmigrate-checkpoint-test-", ".yaml")
        Files.writeString(file, content)
        return file
    }

    test("default config path does not exist — returns null") {
        val resolver = PipelineCheckpointResolver(
            defaultConfigPath = Path.of("/tmp/does-not-exist-${System.nanoTime()}.yaml"),
            envLookup = { null },
        )
        resolver.resolve() shouldBe null
    }

    test("CLI config path does not exist — throws ConfigResolveException") {
        val ex = shouldThrow<ConfigResolveException> {
            PipelineCheckpointResolver(
                configPathFromCli = Path.of("/tmp/no-such-file-${System.nanoTime()}.yaml"),
            ).resolve()
        }
        ex.message shouldContain "Config file not found"
    }

    test("ENV config path does not exist — throws ConfigResolveException") {
        val bogus = "/tmp/env-no-such-${System.nanoTime()}.yaml"
        val ex = shouldThrow<ConfigResolveException> {
            PipelineCheckpointResolver(
                envLookup = { if (it == "D_MIGRATE_CONFIG") bogus else null },
                defaultConfigPath = Path.of("/tmp/ignored.yaml"),
            ).resolve()
        }
        ex.message shouldContain "D_MIGRATE_CONFIG"
        ex.message shouldContain "non-existent"
    }

    test("config file exists but is invalid YAML — throws ConfigResolveException") {
        val file = tempConfig("{{{{ not valid yaml ::::")
        val ex = shouldThrow<ConfigResolveException> {
            PipelineCheckpointResolver(configPathFromCli = file).resolve()
        }
        ex.message shouldContain "Failed to parse"
    }

    test("config file exists but has no pipeline key — returns null") {
        val file = tempConfig(
            """
            database:
              host: localhost
            """.trimIndent()
        )
        PipelineCheckpointResolver(configPathFromCli = file).resolve() shouldBe null
    }

    test("config file exists but has no pipeline.checkpoint key — returns null") {
        val file = tempConfig(
            """
            pipeline:
              chunk_size: 5000
            """.trimIndent()
        )
        PipelineCheckpointResolver(configPathFromCli = file).resolve() shouldBe null
    }

    test("config with all checkpoint fields populated — returns full CheckpointConfig") {
        val file = tempConfig(
            """
            pipeline:
              checkpoint:
                enabled: true
                interval: 5000
                max_interval: "PT10M"
                directory: "/tmp/ckpt"
            """.trimIndent()
        )
        val result = PipelineCheckpointResolver(configPathFromCli = file).resolve()
        result shouldBe CheckpointConfig(
            enabled = true,
            rowInterval = 5000L,
            maxInterval = Duration.ofMinutes(10),
            directory = Path.of("/tmp/ckpt"),
        )
    }

    test("config with only enabled true — returns CheckpointConfig with defaults") {
        val file = tempConfig(
            """
            pipeline:
              checkpoint:
                enabled: true
            """.trimIndent()
        )
        val result = PipelineCheckpointResolver(configPathFromCli = file).resolve()
        result shouldBe CheckpointConfig(
            enabled = true,
            rowInterval = CheckpointConfig.DEFAULT_ROW_INTERVAL,
            maxInterval = CheckpointConfig.DEFAULT_MAX_INTERVAL,
            directory = null,
        )
    }

    test("invalid ISO-8601 duration — throws ConfigResolveException") {
        val file = tempConfig(
            """
            pipeline:
              checkpoint:
                enabled: true
                max_interval: "not-a-duration"
            """.trimIndent()
        )
        val ex = shouldThrow<ConfigResolveException> {
            PipelineCheckpointResolver(configPathFromCli = file).resolve()
        }
        ex.message shouldContain "not a valid ISO-8601 duration"
        ex.message shouldContain "not-a-duration"
    }

    test("config file is not a Map — returns null") {
        val file = tempConfig("just a plain string")
        PipelineCheckpointResolver(configPathFromCli = file).resolve() shouldBe null
    }
})
