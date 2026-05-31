package dev.dmigrate.format.data.json

import dev.dmigrate.format.data.perf.LargeJsonFixture
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
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
 * LF-009 / LF-013 / LN-043: Streaming-Test gegen das 100-MB-Fixture.
 *
 * Verifiziert, dass [JsonChunkReader] das große Fixture mit konstantem
 * Speicherbudget lesen kann und die Integer-vs-Decimal-Diskriminierung
 * korrekt durch den Reader propagiert.
 *
 * Quality-Coverage-Expansion Phase-A-Vervollstaendigung +
 * F3-Followup (2026-05-31): wall-clock laeuft jetzt durch
 * [PerfMeasure]/[PerfReport] statt ad-hoc; das Heap-Budget bleibt
 * separat gemessen (Constant-Memory-Vertrag liegt orthogonal zur
 * Latenz-Trend-Erfassung).
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

        val sample = PerfMeasure.run(warmup = 0, iterations = 1) {
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
                    rowsSeen
                }
            }
        }
        // The closure-captured locals (rowsSeen, firstId, …) only produce
        // coherent measurements at iterations == 1. Catch a future maintainer
        // who bumps iterations without redesigning the captures.
        require(sample.iterations == 1) {
            "JsonChunkReaderPerfTest requires iterations == 1; closure-captured " +
                "rowsSeen/firstId/firstScore deltas would accumulate across iterations."
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

        // Trend-tracking: write the single-iteration wall-clock to the
        // shared perf report. Smoke budget is intentionally generous —
        // 100 MB streaming reads on cold-CI JVMs tail past 30 s. Baseline
        // is set near the observed local figure.
        PerfReport.write(
            hotpath = "format-json-chunk-reader-100mb",
            sample = sample,
            smokeMaxMs = 60_000.0,
            baselineMs = 10_000.0,
        )
    }
})
