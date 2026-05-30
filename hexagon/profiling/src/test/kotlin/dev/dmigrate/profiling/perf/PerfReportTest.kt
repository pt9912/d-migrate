package dev.dmigrate.profiling.perf

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class PerfReportTest : FunSpec({

    fun tempReportRoot(): Path {
        val dir = Files.createTempDirectory("perf-report-")
        dir.toFile().deleteOnExit()
        return dir
    }

    test("write creates directory and target file with deterministic JSON shape") {
        val root = tempReportRoot().resolve("nested/missing/dir")
        val sample = PerfSample.of(
            longArrayOf(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 5_000_000L),
        )
        val target = PerfReport.write(
            hotpath = "schema-migrate-render-pipeline",
            sample = sample,
            smokeMaxMs = 500.0,
            baselineMs = 250.0,
            reportRoot = root,
            timestamp = Instant.parse("2026-05-30T12:34:56.000Z"),
        )

        target.fileName.toString() shouldBe "schema-migrate-render-pipeline.json"
        target.parent shouldBe root
        Files.exists(target) shouldBe true

        val json = Files.readString(target)
        listOf(
            "\"hotpath\":\"schema-migrate-render-pipeline\"",
            "\"timestamp\":\"2026-05-30T12:34:56Z\"",
            "\"iterations\":5",
            "\"medianMs\":3.0",
            "\"p95Ms\":5.0",
            "\"p99Ms\":5.0",
            "\"minMs\":1.0",
            "\"maxMs\":5.0",
            "\"smokeMaxMs\":500.0",
            "\"baselineMs\":250.0",
        ).forEach { fragment ->
            withClue(fragment) { json shouldContain fragment }
        }
        json.trim() shouldEndWith "}"
    }

    test("write overwrites an existing report so nightly runs do not accumulate") {
        val root = tempReportRoot()
        val firstSample = PerfSample.of(longArrayOf(1_000_000L))
        val secondSample = PerfSample.of(longArrayOf(9_000_000L))
        PerfReport.write(
            "hp",
            firstSample,
            10.0,
            5.0,
            reportRoot = root,
            timestamp = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val target = PerfReport.write(
            "hp",
            secondSample,
            10.0,
            5.0,
            reportRoot = root,
            timestamp = Instant.parse("2026-05-30T00:00:00Z"),
        )
        val json = Files.readString(target)
        json shouldContain "\"medianMs\":9.0"
        json shouldContain "\"timestamp\":\"2026-05-30T00:00:00Z\""
        json shouldNotContain "2026-01-01"
    }

    test("write rejects blank hotpath") {
        shouldThrow<IllegalArgumentException> {
            PerfReport.write(
                hotpath = "  ",
                sample = PerfSample.of(longArrayOf(1L)),
                smokeMaxMs = 1.0,
                baselineMs = 1.0,
                reportRoot = tempReportRoot(),
            )
        }
    }

    test("write rejects hotpath with disallowed characters") {
        listOf("SchemaMigrate", "schema_migrate", "schema migrate", "-leading", "trailing-").forEach { bad ->
            shouldThrow<IllegalArgumentException> {
                PerfReport.write(
                    hotpath = bad,
                    sample = PerfSample.of(longArrayOf(1L)),
                    smokeMaxMs = 1.0,
                    baselineMs = 1.0,
                    reportRoot = tempReportRoot(),
                )
            }
        }
    }

    test("write accepts the canonical hotpath forms") {
        val root = tempReportRoot()
        val sample = PerfSample.of(longArrayOf(1_000_000L))
        listOf("diff-planner", "schema-migrate-render-pipeline", "rollback-artefact-round-trip").forEach { hp ->
            PerfReport.write(hp, sample, 1.0, 0.5, reportRoot = root)
            Files.exists(root.resolve("$hp.json")) shouldBe true
        }
    }

    test("renderJson is deterministic for a given input") {
        val sample = PerfSample.of(longArrayOf(1_000_000L, 2_000_000L))
        val ts = Instant.parse("2026-05-30T00:00:00.000Z")
        val a = PerfReport.renderJson("hp", sample, 10.0, 5.0, ts)
        val b = PerfReport.renderJson("hp", sample, 10.0, 5.0, ts)
        a shouldBe b
    }

    test("formatDouble emits integer-valued doubles with explicit .0 suffix") {
        PerfReport.formatDouble(1.0) shouldBe "1.0"
        PerfReport.formatDouble(0.0) shouldBe "0.0"
        PerfReport.formatDouble(42.0) shouldBe "42.0"
    }

    test("formatDouble trims trailing zeros from fractional values") {
        PerfReport.formatDouble(1.5) shouldBe "1.5"
        PerfReport.formatDouble(2.125) shouldBe "2.125"
    }

    test("formatDouble rejects NaN and infinities") {
        shouldThrow<IllegalArgumentException> { PerfReport.formatDouble(Double.NaN) }
        shouldThrow<IllegalArgumentException> { PerfReport.formatDouble(Double.POSITIVE_INFINITY) }
        shouldThrow<IllegalArgumentException> { PerfReport.formatDouble(Double.NEGATIVE_INFINITY) }
    }

    test("DEFAULT_REPORT_ROOT points at build/reports/perf") {
        PerfReport.DEFAULT_REPORT_ROOT.toString().replace('\\', '/') shouldBe "build/reports/perf"
    }

    test("written JSON contains exactly the contract field set") {
        val root = tempReportRoot()
        PerfReport.write(
            hotpath = "hp",
            sample = PerfSample.of(longArrayOf(1_000_000L)),
            smokeMaxMs = 1.0,
            baselineMs = 0.5,
            reportRoot = root,
        )
        val json = Files.readString(root.resolve("hp.json"))
        val keys = Regex("\"([a-zA-Z][a-zA-Z0-9]*)\":").findAll(json).map { it.groupValues[1] }.toList()
        keys shouldContainAll listOf(
            "hotpath", "timestamp", "iterations",
            "medianMs", "p95Ms", "p99Ms", "minMs", "maxMs",
            "smokeMaxMs", "baselineMs",
        )
    }
})
