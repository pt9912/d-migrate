package dev.dmigrate.format.parquet.spike

import dev.dmigrate.core.data.ColumnDescriptor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.util.Comparator

/**
 * AP6-Spike-Verifikation: Importpfad-Bausteine.
 *
 * Belegt fuer
 * `docs/planning/done-archive/parquet-export-import-evaluation.md`
 * §8 Arbeitspaket 6 drei konkrete Eigenschaften am Spike-Output:
 *
 * 1. Der Parquet-Footer reicht als Schema-Quelle, um neutrale
 *    [ColumnDescriptor]-Tupel zu erzeugen — der produktive Adapter
 *    braucht dafuer keinen separaten Manifest-Pfad
 *    (Sidecar/Single-File-Vertrag aus §6 ist davon unberuehrt).
 * 2. Spike-Rows lassen sich durch das neutrale `DataChunk`-Modell
 *    leiten — der `ParquetChunkReader`-Vertrag aus
 *    `parquet-libraries.md` §7.1 funktioniert prototypisch.
 * 3. Der `.crc`-Sidecar-Befund aus `parquet-libraries.md` §7 laesst
 *    sich durch `fs.file.impl=RawLocalFileSystem` auf der
 *    Writer-Seite vollstaendig vermeiden.
 */
class ParquetSpikeImportPathTest : FunSpec({

    test("readSchemaFromFooter liefert ColumnDescriptors in Spike-Spaltenreihenfolge") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-importpath-schema-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            ParquetSpike.write(
                file,
                listOf(ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true)),
            )

            val columns = ParquetSpike.readSchemaFromFooter(file)

            columns shouldContainExactly listOf(
                ColumnDescriptor(name = "id", nullable = false, sqlTypeName = "INT32"),
                ColumnDescriptor(name = "name", nullable = false, sqlTypeName = "BINARY (STRING)"),
                ColumnDescriptor(name = "active", nullable = false, sqlTypeName = "BOOLEAN"),
            )
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    test("readAsChunk liefert DataChunk mit Spike-Rows als Array<Any?>") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-importpath-chunk-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            val rows = listOf(
                ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true),
                ParquetSpike.SpikeRow(id = 2, name = "bravo", active = false),
                ParquetSpike.SpikeRow(id = 3, name = "charlie with spaces", active = true),
            )
            ParquetSpike.write(file, rows)

            val chunk = ParquetSpike.readAsChunk(file, tableName = "spike_table")

            chunk.table shouldBe "spike_table"
            chunk.chunkIndex shouldBe 0L
            chunk.columns.map { it.name } shouldContainExactly listOf("id", "name", "active")
            chunk.rows.size shouldBe 3

            chunk.rows[0][0] shouldBe 1
            chunk.rows[0][1] shouldBe "alpha"
            chunk.rows[0][2] shouldBe true

            chunk.rows[1][0] shouldBe 2
            chunk.rows[1][1] shouldBe "bravo"
            chunk.rows[1][2] shouldBe false

            chunk.rows[2][0] shouldBe 3
            chunk.rows[2][1] shouldBe "charlie with spaces"
            chunk.rows[2][2] shouldBe true
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    test("writeWithoutCrc unterdrueckt den .crc-Sidecar") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-importpath-nocrc-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            ParquetSpike.writeWithoutCrc(
                file,
                listOf(ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true)),
            )

            Files.exists(file) shouldBe true
            // Die Parquet-Datei selbst muss da sein und intakt; Sidecar darf nicht.
            val siblings = Files.list(tmpDir).use { it.toList() }
            siblings.any { it.fileName.toString().endsWith(".crc") } shouldBe false

            // Round-Trip-Read funktioniert auch ohne Sidecar — die
            // RawLocalFileSystem-Variante schreibt eine vollstaendige
            // Parquet-Datei, nur ohne Hadoop-Checksum-Sidecar.
            ParquetSpike.read(file) shouldContainExactly listOf(
                ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true),
            )
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
