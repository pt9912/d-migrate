package dev.dmigrate.format.data.perf

import com.dslplatform.json.DslJson
import com.dslplatform.json.runtime.Settings
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.floor

private val PerfTag = NamedTag("perf")

/**
 * LN-043: echter Go/No-Go-Spike gegen DSL-JSONs Streaming-/Iterator-API
 * auf einem 100-MB-Top-Level-Array.
 *
 * Ziel:
 * - verifizieren, dass der Pull-Pfad einen großen Input ohne retained-heap-
 *   Wachstum proportional zur Dateigröße konsumieren kann
 * - zusätzlich bestätigen, dass Integer- und Decimal-Felder auf
 *   Reader-/Token-Ebene unterschiedlich und korrekt ansprechbar sind
 *
 * Migration zu PerfMeasure/PerfReport (0.9.7 Quality-Coverage-Expansion
 * Sub-Slice A-Vervollständigung): die einzelne 100-MB-Iteration läuft
 * jetzt durch [PerfMeasure.run] (warmup=0, iterations=1) und der
 * Pull-Pfad emittiert seinen Wall-Clock-Wert über [PerfReport.write] in
 * `build/reports/perf/large-json-pull-spike.json`, damit der
 * Trend-Dashboard die Zahl gegen den Bestand der anderen Hotpaths
 * aggregieren kann. Die retained-heap-Assertion bleibt unverändert —
 * sie ist der eigentliche Vertrag dieses Specs.
 *
 * Der Test ist absichtlich `perf`-getaggt und läuft nur opt-in via
 * `-Dkotest.tags=perf`. Default-CI bleibt davon unberührt.
 */
class LargeJsonPullSpikePerfTest : FunSpec({

    tags(PerfTag)

    test("perf 100MB fixture ensures constant-memory DSL-JSON pull parse") {
        val params = LargeJsonFixture.Params(
            rows = 1_200_000L,
            seed = 42L,
        )
        val fixture = LargeJsonFixture.ensureFixture(
            dir = LargeJsonFixture.defaultCacheDir(),
            name = "phase-a-100mb",
            params = params,
        )
        val fixtureBytes = java.nio.file.Files.size(fixture)

        // Gate: the spike must actually hit the intended size class.
        fixtureBytes shouldBeGreaterThan (100L * 1024L * 1024L)

        val json = DslJson<Any>(Settings.withRuntime<Any>().includeServiceLoader())
        val heapBefore = LargeJsonFixture.usedHeapBytes()

        var rowsSeen = 0L
        var firstId = Long.MIN_VALUE
        var firstScore = Double.NaN
        var lastId = Long.MIN_VALUE
        var maxRetainedHeap = heapBefore

        val sample = PerfMeasure.run(warmup = 0, iterations = 1) {
            java.nio.file.Files.newInputStream(fixture).use { input ->
                val iterator = requireNotNull(
                    json.iterateOver(ProbeRow::class.java, input, ByteArray(64 * 1024))
                ) {
                    "DSL-JSON returned null iterator for top-level array"
                }
                while (iterator.hasNext()) {
                    val row = iterator.next()
                    if (rowsSeen == 0L) {
                        firstId = row.id
                        firstScore = row.score
                    }
                    lastId = row.id
                    rowsSeen++

                    if (rowsSeen % 100_000L == 0L) {
                        val retained = LargeJsonFixture.usedHeapBytes()
                        if (retained > maxRetainedHeap) {
                            maxRetainedHeap = retained
                        }
                    }
                }
            }
            // Returns the per-block working set (small object) so the
            // PerfMeasure.Sink keeps it alive without autoboxing the
            // primitive rowsSeen counter on every measured iteration —
            // not a concern at iterations=1 (the `require` below
            // enforces that), but keeps the pattern transferable.
            ProbeRow().apply { id = lastId }
        }

        // Review finding #3: the var-capture pattern above (rowsSeen,
        // firstId, …, maxRetainedHeap) only produces correct invariants
        // for iterations == 1. Catching a future maintainer who bumps
        // iterations without redesigning the captures.
        require(sample.iterations == 1) {
            "LargeJsonPullSpikePerfTest requires iterations == 1; refactor the var-captured " +
                "rowsSeen / firstId / lastId / maxRetainedHeap into per-iteration scope before " +
                "bumping the sample size."
        }

        val heapAfter = LargeJsonFixture.usedHeapBytes()
        maxRetainedHeap = maxOf(maxRetainedHeap, heapAfter)

        rowsSeen shouldBe params.rows
        lastId shouldBe params.rows - 1

        // Integer-vs-decimal discrimination: the generator emits `id`
        // as JSON integer and `score` as JSON decimal. A correct pull
        // parse must preserve that distinction when bound into the probe.
        firstId shouldBe 0L
        firstScore.shouldBeFinite()
        (firstScore != floor(firstScore)).shouldBeTrue()

        val retainedGrowth = maxRetainedHeap - heapBefore

        // Constant-memory gate: retained heap may grow somewhat due to
        // warmup/runtime buffers, but it must stay far below the 100-MB
        // payload size. 32 MiB leaves room for parser/runtime jitter
        // while still failing any accidental buffer-the-whole-file path.
        retainedGrowth shouldBeLessThan (32L * 1024L * 1024L)

        // Trend-tracking: emit the single-iteration wall-clock so the
        // nightly perf dashboard can plot regressions. The smoke budget
        // is generous (60 s leaves headroom for shared-CI cold start +
        // fixture re-generation); the baseline (12 s) is diagnostic
        // only on shared-CI.
        PerfReport.write(
            hotpath = "large-json-pull-spike",
            sample = sample,
            smokeMaxMs = 60_000.0,
            baselineMs = 12_000.0,
        )
    }
})

class ProbeRow {
    @JvmField
    var id: Long = 0

    @JvmField
    var email: String? = null

    @JvmField
    var score: Double = 0.0

    @JvmField
    var active: Boolean = false

    @JvmField
    var tag: String? = null
}

private fun Double.shouldBeFinite() {
    this.isFinite().shouldBeTrue()
}
