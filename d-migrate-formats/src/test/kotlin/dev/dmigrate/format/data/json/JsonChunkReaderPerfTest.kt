package dev.dmigrate.format.data.json

import dev.dmigrate.format.data.perf.LargeJsonFixture
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.floor

private val PerfTag = NamedTag("perf")

/**
 * Phase B Schritt 7: Streaming-Test gegen das 100-MB-Fixture aus Phase A.
 *
 * Verifiziert, dass [JsonChunkReader] das große Fixture mit konstantem
 * Speicherbudget lesen kann und die Integer-vs-Decimal-Diskriminierung
 * korrekt durch den Reader propagiert.
 *
 * Opt-in via `-Dkotest.tags=perf`. Standard-CI führt diesen Test nicht aus.
 */
class JsonChunkReaderPerfTest : FunSpec({

    tags(PerfTag)

    test("perf 100MB fixture: JsonChunkReader streams with constant memory") {
        val params = LargeJsonFixture.Params(
            rows = 1_200_000L,
            seed = 42L,
        )
        val fixture = LargeJsonFixture.ensureFixture(
            dir = LargeJsonFixture.defaultCacheDir(),
            name = "phase-b-json-reader",
            params = params,
        )
        val fixtureBytes = java.nio.file.Files.size(fixture)
        fixtureBytes shouldBeGreaterThan (100L * 1024L * 1024L)

        val chunkSize = 10_000
        val heapBefore = LargeJsonFixture.usedHeapBytes()

        var rowsSeen = 0L
        var firstId: Any? = null
        var firstScore: Any? = null
        var maxRetainedHeap = heapBefore

        java.nio.file.Files.newInputStream(fixture).use { input ->
            JsonChunkReader(input, "perf", chunkSize).use { reader ->
                reader.headerColumns() shouldBe listOf("id", "email", "score", "active", "tag")

                var chunk = reader.nextChunk()
                while (chunk != null) {
                    for (row in chunk.rows) {
                        if (rowsSeen == 0L) {
                            firstId = row[0]
                            firstScore = row[2]
                        }
                        rowsSeen++
                    }

                    if (rowsSeen % 100_000L == 0L) {
                        val retained = LargeJsonFixture.usedHeapBytes()
                        if (retained > maxRetainedHeap) {
                            maxRetainedHeap = retained
                        }
                    }

                    chunk = reader.nextChunk()
                }
            }
        }

        val heapAfter = LargeJsonFixture.usedHeapBytes()
        maxRetainedHeap = maxOf(maxRetainedHeap, heapAfter)

        // Row count
        rowsSeen shouldBe params.rows

        // Type discrimination: id = Long (integer), score = decimal Number.
        // The concrete decimal carrier depends on DSL-JSON's object conversion
        // and may legitimately be BigDecimal instead of Double.
        firstId.shouldBeInstanceOf<Long>()
        firstId shouldBe 0L

        firstScore.shouldBeInstanceOf<Number>()
        (firstScore is Long) shouldBe false
        val score = (firstScore as Number).toDouble()
        score.isFinite().shouldBeTrue()
        (score != floor(score)).shouldBeTrue()

        // Constant-memory gate: retained heap growth < 32 MiB
        val retainedGrowth = maxRetainedHeap - heapBefore
        retainedGrowth shouldBeLessThan (32L * 1024L * 1024L)
    }
})
