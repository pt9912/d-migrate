package dev.dmigrate.format.parquet.spike

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.Comparator

/**
 * AP3-Spike-Verifikation: Round-Trip durch parquet-java 1.17.1 ohne
 * Hadoop-Runtime, ohne Snappy/ZSTD-Native-Codecs, ohne Avro/Protobuf
 * im Klassenpfad (siehe build.gradle.kts-Exclusions + Constraints).
 */
class ParquetSpikeTest : FunSpec({

    test("round-trip writes and reads three rows with GZIP codec") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-spike-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            val rows = listOf(
                ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true),
                ParquetSpike.SpikeRow(id = 2, name = "bravo", active = false),
                ParquetSpike.SpikeRow(id = 3, name = "charlie with spaces", active = true),
            )

            ParquetSpike.write(file, rows)

            // Datei existiert, ist non-empty, und Parquet-Magic-Header sitzt drin.
            Files.exists(file) shouldBe true
            val bytes = Files.readAllBytes(file)
            bytes.isNotEmpty() shouldBe true
            // Parquet-Footer endet immer mit "PAR1".
            String(bytes, bytes.size - 4, 4) shouldBe "PAR1"
            // Parquet-Header startet ebenfalls mit "PAR1".
            String(bytes, 0, 4) shouldBe "PAR1"

            val readBack = ParquetSpike.read(file)
            readBack shouldBe rows

            // AP3-Befund: Hadoop-LocalFileSystem schreibt eine `.spike.parquet.crc`-
            // Checksum-Sidecar neben die Datendatei. Fuer einen produktiven
            // ChunkWriter-Adapter muss der Output-Pfad das Sidecar entweder
            // mit-aufraeumen oder via `fs.file.impl.disable.cache` /
            // `RawLocalFileSystem` umgehen.
            val siblings = Files.list(tmpDir).use { it.toList() }
            siblings.any { it.fileName.toString().endsWith(".crc") } shouldBe true
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    test("schema definition exposes three fields with expected types") {
        val schema = ParquetSpike.SCHEMA
        schema.fieldCount shouldBe 3
        schema.getFieldName(0) shouldBe "id"
        schema.getFieldName(1) shouldBe "name"
        schema.getFieldName(2) shouldBe "active"
    }
})
