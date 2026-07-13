package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * LN-005 (R2): [ParquetChunkWriter] setzt eine **explizite** Row-Group-Größe
 * (`withRowGroupSize`), statt auf den parquet-java-~128-MB-Default zu fallen.
 * Verifiziert per Footer-Round-Trip: eine winzige Row-Group-Größe erzeugt für
 * dieselbe Datenmenge **mehrere** Row-Groups, der (große) Default **eine**.
 */
class ParquetChunkWriterRowGroupSizeTest : FunSpec({

    val schema = ChunkSchema(
        table = "rows",
        origin = SchemaOrigin.JDBC_METADATA,
        columns = listOf(
            ChunkColumnSchema("id", nullable = false, neutralType = NeutralType.Integer),
            ChunkColumnSchema("payload", nullable = false, neutralType = NeutralType.Text()),
        ),
    )

    // Genug Zeilen, dass selbst die kleine Row-Group mehrfach überläuft.
    val rows: List<Array<Any?>> = (0 until 60_000).map { arrayOf<Any?>(it, "payload-value-$it") }

    fun writeWith(rowGroupBytes: Long, file: Path) {
        Files.deleteIfExists(file)
        Files.newOutputStream(file).use { out ->
            ParquetChunkWriter(out, rowGroupBytes = rowGroupBytes).use { writer ->
                writer.begin(schema.table, schema)
                writer.write(DataChunk(table = schema.table, columns = emptyList(), rows = rows, chunkIndex = 0L))
                writer.end()
            }
        }
    }

    fun rowGroupCount(file: Path): Int {
        val input = HadoopInputFile.fromPath(HadoopPath(file.toUri()), Configuration(false))
        return ParquetFileReader.open(input).use { it.rowGroups.size }
    }

    test("tiny explicit row-group size produces multiple row-groups") {
        val file = Files.createTempFile("rowgroup-tiny-", ".parquet")
        try {
            writeWith(rowGroupBytes = 4L * 1024, file = file) // 4 KiB
            rowGroupCount(file) shouldBeGreaterThan 1
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("default row-group size (32 MiB) keeps the same small data in a single row-group") {
        val file = Files.createTempFile("rowgroup-default-", ".parquet")
        try {
            writeWith(rowGroupBytes = ParquetChunkWriter.DEFAULT_ROW_GROUP_BYTES, file = file)
            rowGroupCount(file) shouldBe 1
        } finally {
            Files.deleteIfExists(file)
        }
    }
})
