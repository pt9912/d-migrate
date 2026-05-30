package dev.dmigrate.profiling.perf

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
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
 * decided by the runner profile (PR / nightly / `perf-stable-runner`),
 * not by this file.
 */
object PerfReport {

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
     * @param hotpath stable slug used as the JSON file stem; must match
     *   the hotpath identifier used in `*_SMOKE_MAX_MS` / `*_BASELINE_MS`
     *   constants so trend reports can correlate.
     * @param sample percentile aggregate from [PerfMeasure.run].
     * @param smokeMaxMs runaway-Smoke budget the Spec asserted against
     *   (median and p95 individually). Written into the report so the
     *   trend dashboard can show whether the budget is still calibrated.
     * @param baselineMs nightly/dedicated-runner expectation. Written as
     *   diagnostic on shared-CI; only enforced when the runner profile
     *   says so.
     * @param reportRoot override for tests; defaults to
     *   [DEFAULT_REPORT_ROOT].
     * @param timestamp injection point for tests; defaults to
     *   [Instant.now] at write time.
     * @return the path the report was written to, for callers that
     *   want to surface it in a Kotest `withClue` or commit log.
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
        Files.newBufferedWriter(
            target,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { writer ->
            writer.write(renderJson(hotpath, sample, smokeMaxMs, baselineMs, timestamp))
            writer.newLine()
        }
        return target
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
     * Render a double in a portable, locale-independent way. We always
     * emit a decimal point so downstream tools can parse the field as
     * `number` even when the value happens to be an integer count of
     * milliseconds. Internal for direct test coverage of the rounding
     * and NaN-guard branches.
     */
    internal fun formatDouble(value: Double): String {
        require(value.isFinite()) {
            "perf report values must be finite, was $value"
        }
        val rounded = String.format(java.util.Locale.ROOT, "%.6f", value)
        val trimmed = rounded.trimEnd('0').trimEnd('.')
        return if (trimmed.contains('.')) trimmed else "$trimmed.0"
    }
}
