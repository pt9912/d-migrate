package dev.dmigrate.streaming

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PipelineConfigTest : FunSpec({

    test("default chunkSize is 10_000") {
        PipelineConfig().chunkSize shouldBe 10_000
    }

    test("custom chunkSize") {
        PipelineConfig(chunkSize = 500).chunkSize shouldBe 500
    }

    test("zero or negative chunkSize is rejected") {
        shouldThrow<IllegalArgumentException> { PipelineConfig(chunkSize = 0) }
        shouldThrow<IllegalArgumentException> { PipelineConfig(chunkSize = -100) }
    }

    // 0.9.0 Phase B (docs/ImpPlan-0.9.0-B.md §4.3): Checkpoint-Defaults
    // und Validierung.

    test("Phase B §4.3: checkpoint defaults match LN-012 (disabled; 10k rows; 5min)") {
        val cfg = PipelineConfig()
        cfg.checkpoint.enabled shouldBe false
        cfg.checkpoint.rowInterval shouldBe CheckpointConfig.DEFAULT_ROW_INTERVAL
        cfg.checkpoint.maxInterval shouldBe CheckpointConfig.DEFAULT_MAX_INTERVAL
        cfg.checkpoint.directory shouldBe null
    }

    test("Phase B §4.3: zero/negative rowInterval is rejected") {
        shouldThrow<IllegalArgumentException> { CheckpointConfig(rowInterval = 0) }
        shouldThrow<IllegalArgumentException> { CheckpointConfig(rowInterval = -1) }
    }

    test("Phase B §4.3: zero/negative maxInterval is rejected") {
        shouldThrow<IllegalArgumentException> {
            CheckpointConfig(maxInterval = java.time.Duration.ZERO)
        }
        shouldThrow<IllegalArgumentException> {
            CheckpointConfig(maxInterval = java.time.Duration.ofSeconds(-1))
        }
    }

    // 0.9.0 Phase B §4.4: zentraler Merge CLI > Config > Default.

    test("Phase B §4.4: merge — CLI directory sticht Config directory") {
        val config = CheckpointConfig(
            enabled = true,
            rowInterval = 5_000L,
            directory = java.nio.file.Path.of("/config-dir"),
        )
        val merged = CheckpointConfig.merge(
            cliDirectory = java.nio.file.Path.of("/cli-dir"),
            config = config,
        )
        merged.directory shouldBe java.nio.file.Path.of("/cli-dir")
        // Andere Felder kommen weiterhin aus Config
        merged.enabled shouldBe true
        merged.rowInterval shouldBe 5_000L
    }

    test("Phase B §4.4: merge — Config directory bleibt, wenn kein CLI-Override") {
        val config = CheckpointConfig(directory = java.nio.file.Path.of("/config-dir"))
        CheckpointConfig.merge(cliDirectory = null, config = config).directory shouldBe
            java.nio.file.Path.of("/config-dir")
    }

    test("Phase B §4.4: merge — ohne Quellen greifen Defaults") {
        val merged = CheckpointConfig.merge()
        merged shouldBe CheckpointConfig()
    }
})
