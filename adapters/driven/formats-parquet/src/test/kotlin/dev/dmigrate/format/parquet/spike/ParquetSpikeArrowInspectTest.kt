package dev.dmigrate.format.parquet.spike

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.arrow.schema.SchemaConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.nio.file.Files
import java.util.Comparator

/**
 * AP5-Akzeptanz: Arrow-Java-Metadateninspektion des Spike-Outputs.
 *
 * Bestaetigt das Akzeptanzkriterium aus
 * `docs/planning/done/parquet-export-import-evaluation.md` §7
 * Bullet 2 ("Der Beispiel-Export kann mit Arrow-Werkzeugen oder
 * Arrow-Java-Metadaten inspiziert werden") gegen den AP3-Spike.
 *
 * Konkret: `ParquetFileReader` liest den Datei-Footer; `parquet-arrow`
 * `SchemaConverter#fromParquet` konvertiert den Parquet
 * `MessageType` zu einem Arrow `Schema`-POJO und der Test verifiziert
 * die drei Spike-Spalten samt Arrow-Logical-Types.
 *
 * Bewusst nur Metadateninspektion: laut `parquet-libraries.md` §3.4
 * ist `arrow-dataset` (JNI) fuer den ersten Format-Adapter zu schwer
 * und nicht im Zielbild. `parquet-arrow` ist ein reines JVM-Modul
 * (POJO-Schema + arrow-vector als Compile-Time-Bridge) und kommt
 * ausschliesslich als `testImplementation` ins Modul — kein
 * produktiver Arrow-Pfad.
 */
class ParquetSpikeArrowInspectTest : FunSpec({

    test("Arrow SchemaConverter exposes spike schema as Int(32)/Utf8/Bool") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-arrow-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            ParquetSpike.write(
                file,
                listOf(ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true)),
            )

            val conf = Configuration(false)
            val inputFile = HadoopInputFile.fromPath(HadoopPath(file.toUri()), conf)
            val parquetSchema = ParquetFileReader.open(inputFile).use { reader ->
                reader.fileMetaData.schema
            }

            val arrowSchema = SchemaConverter().fromParquet(parquetSchema).arrowSchema
            val fields = arrowSchema.fields
            fields.size shouldBe 3

            // Spike-Spalte "id": REQUIRED INT32 -> Arrow Int(32, signed)
            fields[0].name shouldBe "id"
            fields[0].isNullable shouldBe false
            val idType = fields[0].type as ArrowType.Int
            idType.bitWidth shouldBe 32
            idType.isSigned shouldBe true

            // Spike-Spalte "name": REQUIRED BINARY mit stringType
            // -> Arrow Utf8 (LogicalTypeAnnotation.stringType() laesst
            // parquet-arrow direkt auf den Utf8-ArrowType abbilden).
            fields[1].name shouldBe "name"
            fields[1].isNullable shouldBe false
            (fields[1].type is ArrowType.Utf8) shouldBe true

            // Spike-Spalte "active": REQUIRED BOOLEAN -> Arrow Bool
            fields[2].name shouldBe "active"
            fields[2].isNullable shouldBe false
            (fields[2].type is ArrowType.Bool) shouldBe true
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
