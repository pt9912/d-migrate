package dev.dmigrate.streaming

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.sqlite.SqliteDataWriter
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory
import dev.dmigrate.profiling.perf.PerfMeasure
import dev.dmigrate.profiling.perf.PerfReport
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.math.roundToLong

private val PerfTag = NamedTag("perf")

/**
 * LF-010 / LF-013: Reorder-Perf-Gate.
 *
 * Verifiziert den Streaming-Import mit Header-Reordering gegen ein
 * 1 000 000-Row-Fixture und echtem SQLite-Zielpfad. Der Test misst
 * Importzeit, Allocation und GC-Druck und gibt die Ergebnisse auf
 * stdout aus — die menschliche Gate-Entscheidung wird in
 * `docs/perf/0.4.0-phase-d-reorder.md` dokumentiert.
 *
 * Opt-in via `-Dkotest.tags=perf`. Standard-CI fuehrt diesen Test nicht aus.
 */
class StreamingImporterReorderPerfTest : FunSpec({

    tags(PerfTag)

    test("reorder path stays below allocation gate or produces explicit contract decision") {
        val rows = 1_000_000L
        val dbFile = Files.createTempFile("d-migrate-perf-reorder-", ".db")
        val jsonFile = Files.createTempFile("d-migrate-perf-fixture-", ".json")

        dbFile.deleteIfExists()
        val pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null,
                port = null,
                database = dbFile.absolutePathString(),
                user = null,
                password = null,
            )
        )

        try {
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        "CREATE TABLE perf_users (" +
                            "id INTEGER PRIMARY KEY, " +
                            "name TEXT, " +
                            "score REAL)"
                    )
                }
            }

            generateReorderFixture(jsonFile, rows)

            val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
            val threadMxBean = ManagementFactory.getThreadMXBean()
            val allocationBean = threadMxBean as? com.sun.management.ThreadMXBean

            usedHeapBytes()
            val heapBefore = usedHeapBytes()

            // Quality-Coverage-Expansion Sub-Slice A-Vervollständigung
            // (2026-05-30) + review finding #2: drive the wall-clock
            // through PerfMeasure (the canonical contract used by the
            // three Phase-A hotpath specs) AND keep the GC / allocation
            // window identical to it. Old code captured GC counters
            // before/after the inner `System.nanoTime()` delta; new
            // code keeps both windows aligned by reading the MX-bean
            // snapshots inside the PerfMeasure block, so the
            // enforceOptionalPerfGate `gcRatio = gcTimeMs / elapsedMs`
            // measures the same wall-clock on both sides.
            val writer = SqliteDataWriter()
            val importer = StreamingImporter(
                readerFactory = DefaultDataChunkReaderFactory(),
                writerLookup = { writer },
            )

            var capturedResult: ImportResult? = null
            var capturedAllocatedBytes: Long? = null
            var capturedGcCountDelta: Long = 0
            var capturedGcTimeDelta: Long = 0
            val sample = PerfMeasure.run(warmup = 0, iterations = 1) {
                val gcCountBefore = gcBeans.sumOf { it.collectionCount }
                val gcTimeBefore = gcBeans.sumOf { it.collectionTime }
                val allocBefore = allocationBean
                    ?.getThreadAllocatedBytes(Thread.currentThread().threadId())

                val outcome = importer.import(
                    pool = pool,
                    input = ImportInput.SingleFile("perf_users", jsonFile),
                    format = DataExportFormat.JSON,
                )

                val gcCountAfter = gcBeans.sumOf { it.collectionCount }
                val gcTimeAfter = gcBeans.sumOf { it.collectionTime }
                val allocAfter = allocationBean
                    ?.getThreadAllocatedBytes(Thread.currentThread().threadId())

                capturedResult = outcome
                capturedAllocatedBytes =
                    if (allocBefore != null && allocAfter != null) allocAfter - allocBefore else null
                capturedGcCountDelta = gcCountAfter - gcCountBefore
                capturedGcTimeDelta = gcTimeAfter - gcTimeBefore
                outcome
            }
            // Review finding #3: the closure-captured locals above only
            // produce coherent measurements at iterations == 1. Catch a
            // future maintainer who bumps iterations without redesigning
            // the captures (each iteration would overwrite the deltas;
            // PK collisions on perf_users would mask the actual perf
            // signal).
            require(sample.iterations == 1) {
                "StreamingImporterReorderPerfTest requires iterations == 1; the single-fixture " +
                    "SQLite target and var-captured GC/alloc deltas need redesigning before bumping " +
                    "the sample size."
            }
            val importResult = checkNotNull(capturedResult) { "importer.import() must have produced a result" }
            val allocatedBytes = capturedAllocatedBytes
            val gcCountDelta = capturedGcCountDelta
            val gcTimeDelta = capturedGcTimeDelta

            usedHeapBytes()
            val heapAfter = usedHeapBytes()

            importResult.totalRowsInserted shouldBe rows

            // Trend-tracking: surface the single-iteration wall-clock
            // alongside the existing stdout summary so the nightly
            // perf dashboard can chart import-time drift even when the
            // optional allocation/GC gate is disabled.
            PerfReport.write(
                hotpath = "streaming-importer-reorder",
                sample = sample,
                smokeMaxMs = 120_000.0,
                baselineMs = 20_000.0,
            )

            // Review finding #12: surface the full-precision median in
            // both the println summary and the JSON report. Old code
            // rounded to whole milliseconds for stdout; the dashboard
            // and the eyeballs now agree.
            val elapsedMs = sample.medianMs
            println(
                """
                |
                |--- Reorder Perf Gate ---
                |Rows:               ${"%,d".format(rows)}
                |Total import time:  ${"%,.3f".format(elapsedMs)} ms
                |Allocated bytes:    ${allocatedBytes?.let { "%,d".format(it / (1024 * 1024)) } ?: "<nicht unterstützt>"} MB
                |Per-row allocation: ${allocatedBytes?.let { "%,d".format(it / rows) } ?: "<nicht unterstützt>"} bytes/row
                |GC count:           $gcCountDelta
                |GC time:            $gcTimeDelta ms
                |Heap before:        ${"%,d".format(heapBefore / (1024 * 1024))} MB
                |Heap after:         ${"%,d".format(heapAfter / (1024 * 1024))} MB
                |-------------------------
                |
                """.trimMargin()
            )

            enforceOptionalPerfGate(
                elapsedMs = elapsedMs.roundToLong(),
                allocatedBytes = allocatedBytes,
                rows = rows,
                gcTimeMs = gcTimeDelta,
            )
        } finally {
            kotlin.runCatching { pool.close() }
            jsonFile.deleteIfExists()
            dbFile.deleteIfExists()
        }
    }
})

/**
 * Generiert ein JSON-Top-Level-Array mit [rows] Objekten. Die Schluessel-
 * Reihenfolge ist `score, name, id` — absichtlich anders als das DB-Schema
 * `id, name, score`, damit der Importer den Reorder-Pfad durchlaeuft.
 */
private fun generateReorderFixture(path: Path, rows: Long) {
    Files.newOutputStream(path).use { out ->
        BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 1 shl 16).use { w ->
            w.write("[\n")
            for (i in 0 until rows) {
                if (i > 0) w.write(",\n")
                w.write("""{"score":${i % 1000}.${i % 100},"name":"user-$i","id":$i}""")
            }
            w.write("\n]")
        }
    }
}

private fun usedHeapBytes(): Long {
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
}

private fun enforceOptionalPerfGate(
    elapsedMs: Long,
    allocatedBytes: Long?,
    rows: Long,
    gcTimeMs: Long,
) {
    val doAssert = System.getProperty("d-migrate.perf.perf-gate", "false").toBoolean()
    if (!doAssert) return

    val maxGcRatioPercent = System.getProperty("d-migrate.perf.max-gc-ratio-percent")
        ?.toDoubleOrNull()
    val maxAllocationPerRowBytes = System.getProperty("d-migrate.perf.max-allocation-bytes-per-row")
        ?.toLongOrNull()
    if (maxGcRatioPercent == null && maxAllocationPerRowBytes == null) {
        throw IllegalStateException(
            "Perf-gate aktiv: set at least one of d-migrate.perf.max-gc-ratio-percent or " +
                "d-migrate.perf.max-allocation-bytes-per-row"
        )
    }

    if (elapsedMs > 0L && maxGcRatioPercent != null) {
        val gcRatio = (gcTimeMs.toDouble() * 100.0) / elapsedMs.toDouble()
        gcRatio shouldBeLessThan maxGcRatioPercent
    }

    if (allocatedBytes != null && maxAllocationPerRowBytes != null) {
        val bytesPerRow = (allocatedBytes.toDouble() / rows.toDouble()).roundToLong()
        bytesPerRow shouldBeLessThan maxAllocationPerRowBytes
    } else if (maxAllocationPerRowBytes != null) {
        throw IllegalStateException(
            "Perf-gate aktiv: Thread-Allocation-Metrik nicht verfügbar auf dieser JVM; " +
                "setze d-migrate.perf.max-allocation-bytes-per-row nicht oder nutze ein JDK, " +
                "das com.sun.management.ThreadMXBean unterstützt"
        )
    }
}
