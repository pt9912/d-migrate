package dev.dmigrate.format.parquet.spike

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetBundleClosure
import dev.dmigrate.format.data.BundleClosureContext
import dev.dmigrate.format.data.BundleClosureTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path as HadoopPath
import org.apache.parquet.arrow.schema.SchemaConverter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * S9a Bundle-Test-Familie **4 (DuckDB-/Arrow-Bundle-KV-Toleranz)**: ein
 * echtes **Produktiv**-Bundle (mehrere `.parquet` + `manifest.yaml`,
 * geschrieben mit dem produktiven [ParquetChunkWriter] **ohne**
 * Footer-KV-Provider) muss von unabhängigen Readern normal lesbar sein.
 *
 * Belegt den AP8-Kontrast zur Single-File-KV-Toleranz: Bundle-Dateien
 * tragen **bewusst kein** `d-migrate.manifest`-Footer-KV (die
 * `manifest.yaml` ist die Quelle), und Fremd-Reader (DuckDB `read_parquet`,
 * Arrow `SchemaConverter`) hängen nicht davon ab. DuckDB/Arrow sind
 * reine Akzeptanz-/Inspektionswerkzeuge (`parquet-libraries.md` §3.4/§3.5),
 * `testImplementation`-only — kein produktiver Reader-Pfad.
 */
class ParquetBundleCrossReaderToleranceTest : FunSpec({

    fun writeBundle(dir: Path) {
        val usersSchema = ChunkSchema(
            table = "users", origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.BigInteger)),
        )
        val ordersSchema = ChunkSchema(
            table = "orders", origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("order_id", false, NeutralType.BigInteger)),
        )
        // Produktiver Writer OHNE extraMetaDataProvider → kein Footer-KV.
        Files.newOutputStream(dir.resolve("users.parquet")).use { out ->
            ParquetChunkWriter(out).use { w ->
                w.begin("users", usersSchema)
                w.write(DataChunk(table = "users", columns = emptyList(), rows = listOf(arrayOf<Any?>(1L)), chunkIndex = 0L))
                w.end()
            }
        }
        Files.newOutputStream(dir.resolve("orders.parquet")).use { out ->
            ParquetChunkWriter(out).use { w ->
                w.begin("orders", ordersSchema)
                w.write(DataChunk(table = "orders", columns = emptyList(), rows = listOf(arrayOf<Any?>(10L)), chunkIndex = 0L))
                w.end()
            }
        }
        val fixedClock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC)
        ParquetBundleClosure(producerVersion = "0.9.8", manifestSha256 = true, clock = fixedClock)(
            BundleClosureContext(
                directory = dir,
                format = DataExportFormat.PARQUET,
                tables = listOf(
                    BundleClosureTable("users", dir.resolve("users.parquet"), usersSchema, rowCount = 1),
                    BundleClosureTable("orders", dir.resolve("orders.parquet"), ordersSchema, rowCount = 1),
                ),
            ),
        )
    }

    fun footerKeyValues(file: Path): Map<String, String> {
        val inputFile = HadoopInputFile.fromPath(HadoopPath(file.toUri()), Configuration(false))
        return ParquetFileReader.open(inputFile).use { it.fileMetaData.keyValueMetaData ?: emptyMap() }
    }

    test("Bundle-.parquet tragen kein d-migrate-Footer-KV (Quelle ist manifest.yaml)") {
        val dir = Files.createTempDirectory("s9a4-nokv-")
        try {
            writeBundle(dir)
            Files.exists(dir.resolve("manifest.yaml")) shouldBe true
            for (f in listOf("users.parquet", "orders.parquet")) {
                footerKeyValues(dir.resolve(f)).containsKey("d-migrate.manifest") shouldBe false
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("DuckDB read_parquet liest die Bundle-Dateien trotz manifest.yaml daneben") {
        val dir = Files.createTempDirectory("s9a4-duckdb-")
        try {
            writeBundle(dir)
            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.prepareStatement("SELECT id FROM read_parquet(?) ORDER BY id").use { stmt ->
                    stmt.setString(1, dir.resolve("users.parquet").toAbsolutePath().toString())
                    stmt.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getLong("id") shouldBe 1L
                    }
                }
                conn.prepareStatement("SELECT order_id FROM read_parquet(?)").use { stmt ->
                    stmt.setString(1, dir.resolve("orders.parquet").toAbsolutePath().toString())
                    stmt.executeQuery().use { rs ->
                        rs.next() shouldBe true
                        rs.getLong("order_id") shouldBe 10L
                    }
                }
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("Arrow SchemaConverter inspiziert die Bundle-Dateien (ohne Footer-KV)") {
        val dir = Files.createTempDirectory("s9a4-arrow-")
        try {
            writeBundle(dir)
            val inputFile = HadoopInputFile.fromPath(HadoopPath(dir.resolve("users.parquet").toUri()), Configuration(false))
            val parquetSchema = ParquetFileReader.open(inputFile).use { it.fileMetaData.schema }
            val arrowSchema = SchemaConverter().fromParquet(parquetSchema).arrowSchema
            arrowSchema.fields.map { it.name } shouldBe listOf("id")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
