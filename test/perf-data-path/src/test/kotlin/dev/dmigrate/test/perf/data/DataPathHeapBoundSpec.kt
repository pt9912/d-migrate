package dev.dmigrate.test.perf.data

import dev.dmigrate.format.data.csv.CsvChunkWriter
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.OutputStream

private val PerfTag = NamedTag("perf")

/**
 * LN-005 Akzeptanz: der Streaming-Datenpfad hält **bounded memory** (∝ chunkSize,
 * nicht ∝ Zeilenzahl) — die Kernaussage „>10 TB ohne OOM", ohne echte >10-TB-Quelle.
 *
 * Aufbau: ein [SyntheticDataReader] generiert lazy ~1 GiB synthetische Daten
 * (`ROWS × PAYLOAD_WIDTH`), die durch den echten Chunk-Loop (+ realer
 * [CsvChunkWriter]) in einen verwerfenden Sink laufen. Das Modul läuft unter
 * `-Xmx 256m` (build.gradle.kts): das Gesamtvolumen liegt **weit über** dem Heap
 * (~4×), der Peak eines bounded Laufs bei ~`CHUNK_SIZE` Zeilen (~21 MiB) weit
 * darunter. Eine „hält-alles"-Regression (Reader-Emit, Writer-Buffer oder
 * Aggregation) sprengt damit den Heap (OOM + HeapDump); ein bounded Pfad läuft
 * durch. `perf`-getaggt → opt-in via `make docker-perf`.
 */
class DataPathHeapBoundSpec : FunSpec({

    tags(PerfTag)

    // -Xmx 256m → Zielvolumen ≥ 4× = 1 GiB. ROWS × PAYLOAD_WIDTH (Latin1, ~1 B/char)
    // ≈ 500_000 × 2048 ≈ 1 GiB Nutzdaten (+ Boxing/Overhead darüber).
    val rows = 500_000L
    val payloadWidth = 2048
    val chunkSize = 10_000

    /** Verwerfender Sink: zählt nur Bytes, hält keine Daten. */
    fun discardingSink(): Pair<OutputStream, () -> Long> {
        var bytes = 0L
        val out = object : OutputStream() {
            override fun write(b: Int) { bytes++ }
            override fun write(b: ByteArray, off: Int, len: Int) { bytes += len }
        }
        return out to { bytes }
    }

    test("export path (lazy source → real CsvChunkWriter → discarding sink) stays within the heap cap") {
        val reader = SyntheticDataReader(rowCount = rows, payloadWidth = payloadWidth)
        val (sink, byteCount) = discardingSink()
        var seen = 0L
        reader.streamTable(NoopConnectionPool, "synthetic", null, chunkSize).use { seq ->
            CsvChunkWriter(sink).use { writer ->
                writer.begin(seq.schema.table, seq.schema)
                for (chunk in seq) {
                    seen += chunk.rows.size
                    writer.write(chunk)
                }
                writer.end()
            }
        }
        seen shouldBe rows
        (byteCount() > 0L) shouldBe true
    }

    test("source read (shared export/transfer path): draining the lazy sequence stays within the heap cap") {
        val reader = SyntheticDataReader(rowCount = rows, payloadWidth = payloadWidth)
        var seen = 0L
        reader.streamTable(NoopConnectionPool, "synthetic", null, chunkSize).use { seq ->
            for (chunk in seq) seen += chunk.rows.size
        }
        seen shouldBe rows
    }
})
