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
})
