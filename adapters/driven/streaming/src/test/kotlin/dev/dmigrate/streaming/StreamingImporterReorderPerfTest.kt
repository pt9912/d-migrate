package dev.dmigrate.streaming

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.sqlite.SqliteDataWriter
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

private val PerfTag = NamedTag("perf")

/**
 * Phase D Schritt 23: Reorder-Perf-Gate.
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
            val gcCountBefore = gcBeans.sumOf { it.collectionCount }
            val gcTimeBefore = gcBeans.sumOf { it.collectionTime }

            val threadMxBean = ManagementFactory.getThreadMXBean()
                as com.sun.management.ThreadMXBean
            val allocBefore = threadMxBean.getThreadAllocatedBytes(Thread.currentThread().threadId())

            usedHeapBytes()
            val heapBefore = usedHeapBytes()

            val startNanos = System.nanoTime()

            val writer = SqliteDataWriter()
            val importer = StreamingImporter(
                readerFactory = DefaultDataChunkReaderFactory(),
                writerLookup = { writer },
            )

            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("perf_users", jsonFile),
                format = DataExportFormat.JSON,
            )

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

            val allocAfter = threadMxBean.getThreadAllocatedBytes(Thread.currentThread().threadId())
            val allocatedBytes = allocAfter - allocBefore

            usedHeapBytes()
            val heapAfter = usedHeapBytes()

            val gcCountAfter = gcBeans.sumOf { it.collectionCount }
            val gcTimeAfter = gcBeans.sumOf { it.collectionTime }

            result.totalRowsInserted shouldBe rows

            println(
                """
                |
                |--- Reorder Perf Gate ---
                |Rows:               ${"%,d".format(rows)}
                |Total import time:  ${"%,d".format(elapsedMs)} ms
                |Allocated bytes:    ${"%,d".format(allocatedBytes / (1024 * 1024))} MB
                |Per-row allocation: ${"%,d".format(allocatedBytes / rows)} bytes/row
                |GC count:           ${gcCountAfter - gcCountBefore}
                |GC time:            ${gcTimeAfter - gcTimeBefore} ms
                |Heap before:        ${"%,d".format(heapBefore / (1024 * 1024))} MB
                |Heap after:         ${"%,d".format(heapAfter / (1024 * 1024))} MB
                |-------------------------
                |
                """.trimMargin()
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
    System.gc()
    Thread.sleep(50)
    System.gc()
    Thread.sleep(50)
    val rt = Runtime.getRuntime()
    return rt.totalMemory() - rt.freeMemory()
}
