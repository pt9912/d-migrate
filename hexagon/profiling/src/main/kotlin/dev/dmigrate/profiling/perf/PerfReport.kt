package dev.dmigrate.profiling.perf

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Writes the per-hotpath JSON report consumed by the nightly perf
 * trend dashboard and the `make docker-perf` summary. One file per
 * hotpath under `build/reports/perf/<hotpath>.json` so a single Gradle
 * project can host multiple PerfSpecs without collisions.
 *
 * The JSON shape is intentionally hand-rolled (no DSL-JSON / no
 * kotlinx-serialization) to keep the lib dependency-free; every other
 * profiling module already pulls in the JDK time/IO surface used here.
 *
 * **Smoke vs. Baseline contract** — both values are written into the
 * same record so the trend dashboard can plot baseline drift and
 * runaway-Smoke breaches in one chart. The Spec itself only asserts
 * against `smokeMaxMs`; whether `baselineMs` becomes a hard gate is
 * decided by the runner profile.
 *
 * **Baseline gate** — when the system property `d-migrate.perf.gate`
 * is set to `true` (typically via `make docker-perf PERF_GATE=true`,
 * which forwards `-PperfGate=true` through the root `build.gradle.kts`
 * to the forked test JVM), [write] additionally asserts that
 * `sample.medianMs` and `sample.p95Ms` stay at or below `baselineMs`.
 * Without the property, `baselineMs` stays purely diagnostic. Review
 * finding #1.
 *
 * **Iterations=1 caveat** — single-shot specs (e.g. the migrated
 * LargeJson and Streaming-Importer specs) call [PerfMeasure.run] with
 * `iterations=1`. The emitted JSON contains five identical percentile
 * fields (medianMs == p95Ms == p99Ms == minMs == maxMs) because all
 * derive from the single timing. Treat the tail fields as duplicates
 * of `medianMs` for those hotpaths. Review finding #8.
 */
object PerfReport {

    private const val GATE_PROPERTY = "d-migrate.perf.gate"

    /**
     * Default location for the JSON report, resolved from the working
     * directory of the forked test JVM. Gradle sets that to the
     * project root for `test`, so the file lands under
     * `<module>/build/reports/perf/<hotpath>.json`.
     */
    val DEFAULT_REPORT_ROOT: Path = Paths.get("build", "reports", "perf")

    /**
     * Write a single perf record. The report root is created if
     * missing; an existing file at the same path is overwritten so
     * repeated nightly runs do not accumulate per-iteration noise.
     *
     * The write is atomic: the JSON body is staged in `<hotpath>.json.tmp`
     * and then moved with [StandardCopyOption.ATOMIC_MOVE] +
     * [StandardCopyOption.REPLACE_EXISTING] so an ingestion job
     * polling the report directory never observes a partially-written
     * file. Review finding #9.
     *
     * @param hotpath stable slug used as the JSON file stem; must match
     *   the hotpath identifier used in `*_SMOKE_MAX_MS` / `*_BASELINE_MS`
     *   constants so trend reports can correlate.
     * @param sample percentile aggregate from [PerfMeasure.run].
     * @param smokeMaxMs runaway-Smoke budget the Spec asserted against
     *   (median and p95 individually). Written into the report so the
     *   trend dashboard can show whether the budget is still calibrated.
     * @param baselineMs nightly/dedicated-runner expectation. Diagnostic
     *   by default; turned into a hard assertion when the system
     *   property `d-migrate.perf.gate=true` is set.
     * @param reportRoot override for tests; defaults to
     *   [DEFAULT_REPORT_ROOT].
     * @param timestamp injection point for tests; defaults to
     *   [Instant.now] at write time.
     * @return the path the report was written to, for callers that
     *   want to surface it in a Kotest `withClue` or commit log.
     * @throws AssertionError when the baseline gate is active and the
     *   sample exceeds `baselineMs` on `medianMs` or `p95Ms`.
     */
    fun write(
        hotpath: String,
        sample: PerfSample,
        smokeMaxMs: Double,
        baselineMs: Double,
        reportRoot: Path = DEFAULT_REPORT_ROOT,
        timestamp: Instant = Instant.now(),
    ): Path {
        require(hotpath.isNotBlank()) { "hotpath must not be blank" }
        require(VALID_HOTPATH.matches(hotpath)) {
            "hotpath must match $VALID_HOTPATH, was '$hotpath'"
        }
        Files.createDirectories(reportRoot)
        val target = reportRoot.resolve("$hotpath.json")
        val staging = reportRoot.resolve("$hotpath.json.tmp")
        Files.newBufferedWriter(
            staging,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { writer ->
            writer.write(renderJson(hotpath, sample, smokeMaxMs, baselineMs, timestamp))
            writer.newLine()
        }
        moveAtomically(staging, target)
        enforceBaselineGate(hotpath, sample, baselineMs)
        return target
    }

    private fun moveAtomically(staging: Path, target: Path) {
        try {
            Files.move(
                staging,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            // Some build runners mount build/ across filesystems (tmpfs +
            // overlayfs). Fall back to a non-atomic replace so the report
            // still lands; the ingestion-race window is tiny in practice.
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            // Best-effort note in the JVM err log so the ingestion-race
            // gap is at least diagnosable when it bites.
            System.err.println(
                "PerfReport: ATOMIC_MOVE not supported on this filesystem; " +
                    "fell back to non-atomic replace for $target (${e.message})"
            )
        }
    }

    /**
     * Hard gate when the operator opted in via `PERF_GATE=true` /
     * `-Dd-migrate.perf.gate=true`. We assert both median and p95
     * separately so a single ugly tail does not slip past a budget
     * that the median otherwise satisfies. Review finding #1.
     */
    private fun enforceBaselineGate(hotpath: String, sample: PerfSample, baselineMs: Double) {
        if (!System.getProperty(GATE_PROPERTY, "false").toBoolean()) return
        check(sample.medianMs <= baselineMs) {
            "perfGate: hotpath='$hotpath' medianMs=${sample.medianMs} exceeds baselineMs=$baselineMs"
        }
        check(sample.p95Ms <= baselineMs) {
            "perfGate: hotpath='$hotpath' p95Ms=${sample.p95Ms} exceeds baselineMs=$baselineMs"
        }
    }

    private val VALID_HOTPATH = Regex("[a-z0-9][a-z0-9-]*[a-z0-9]")

    internal fun renderJson(
        hotpath: String,
        sample: PerfSample,
        smokeMaxMs: Double,
        baselineMs: Double,
        timestamp: Instant,
    ): String {
        val ts = DateTimeFormatter.ISO_INSTANT.format(timestamp)
        return buildString {
            append('{')
            appendString("hotpath", hotpath); append(',')
            appendString("timestamp", ts); append(',')
            appendInt("iterations", sample.iterations); append(',')
            appendDouble("medianMs", sample.medianMs); append(',')
            appendDouble("p95Ms", sample.p95Ms); append(',')
            appendDouble("p99Ms", sample.p99Ms); append(',')
            appendDouble("minMs", sample.minMs); append(',')
            appendDouble("maxMs", sample.maxMs); append(',')
            appendDouble("smokeMaxMs", smokeMaxMs); append(',')
            appendDouble("baselineMs", baselineMs)
            append('}')
        }
    }

    private fun StringBuilder.appendString(name: String, value: String) {
        append('"').append(name).append("\":\"").append(value).append('"')
    }

    private fun StringBuilder.appendInt(name: String, value: Int) {
        append('"').append(name).append("\":").append(value)
    }

    private fun StringBuilder.appendDouble(name: String, value: Double) {
        append('"').append(name).append("\":").append(formatDouble(value))
    }

    /**
     * Render a double in a portable, locale-independent way at
     * nanosecond resolution. We always emit a decimal point so
     * downstream tools can parse the field as `number` even when the
     * value happens to be an integer count of milliseconds. Internal
     * for direct test coverage of the rounding and NaN-guard branches.
     *
     * 9 fractional digits = 1 ns precision (since values are
     * millisecond-valued). At 6 digits a 300 ns hotpath collapses to
     * `"0.0"`, hiding a real regression to 900 ns under the same
     * `"0.0"` — review finding #11.
     */
    internal fun formatDouble(value: Double): String {
        require(value.isFinite()) {
            "perf report values must be finite, was $value"
        }
        val rounded = String.format(java.util.Locale.ROOT, "%.9f", value)
        val trimmed = rounded.trimEnd('0').trimEnd('.')
        return if (trimmed.contains('.')) trimmed else "$trimmed.0"
    }
}
