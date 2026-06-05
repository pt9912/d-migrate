package dev.dmigrate.format.parquet.spike

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.Types
import java.util.Comparator

/**
 * AP4-Akzeptanz: DuckDB liest den AP3-Spike-Parquet-Output.
 *
 * Bestaetigt das Akzeptanzkriterium aus
 * `docs/planning/in-progress/parquet-export-import-evaluation.md` §7
 * Bullet 1 ("Ein Beispiel-Export kann mit DuckDB gelesen werden") und
 * deckt fuer das Spike-Schema (int + UTF-8-string + boolean) implizit
 * Bullet 3 (verlustfreier Round-Trip fuer Kern-Datentypen) ab.
 *
 * DuckDB ist ausdruecklich Akzeptanz- und Inspektionswerkzeug
 * (`parquet-libraries.md` §3.5), nicht produktiver Reader. Deshalb
 * laeuft das hier nur als testImplementation-Dependency in
 * `adapters/driven/formats-parquet/build.gradle.kts`.
 */
class ParquetSpikeDuckDbReadTest : FunSpec({

    test("DuckDB read_parquet returns spike rows in order") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-duckdb-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            val rows = listOf(
                ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true),
                ParquetSpike.SpikeRow(id = 2, name = "bravo", active = false),
                ParquetSpike.SpikeRow(id = 3, name = "charlie with spaces", active = true),
            )
            ParquetSpike.write(file, rows)

            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.prepareStatement(
                    "SELECT id, name, active FROM read_parquet(?) ORDER BY id",
                ).use { stmt ->
                    stmt.setString(1, file.toAbsolutePath().toString())
                    stmt.executeQuery().use { rs ->
                        val readBack = mutableListOf<ParquetSpike.SpikeRow>()
                        while (rs.next()) {
                            readBack += ParquetSpike.SpikeRow(
                                id = rs.getInt("id"),
                                name = rs.getString("name"),
                                active = rs.getBoolean("active"),
                            )
                        }
                        readBack shouldBe rows
                    }
                }
            }
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }

    test("DuckDB exposes Parquet column types as INTEGER / VARCHAR / BOOLEAN") {
        val tmpDir = Files.createTempDirectory("d-migrate-parquet-duckdb-types-")
        val file = tmpDir.resolve("spike.parquet")
        try {
            ParquetSpike.write(
                file,
                listOf(ParquetSpike.SpikeRow(id = 1, name = "alpha", active = true)),
            )

            DriverManager.getConnection("jdbc:duckdb:").use { conn ->
                conn.prepareStatement(
                    "SELECT id, name, active FROM read_parquet(?) LIMIT 0",
                ).use { stmt ->
                    stmt.setString(1, file.toAbsolutePath().toString())
                    stmt.executeQuery().use { rs ->
                        val meta = rs.metaData
                        meta.columnCount shouldBe 3

                        // Spike-Schema: REQUIRED INT32 -> DuckDB INTEGER (Types.INTEGER).
                        meta.getColumnLabel(1) shouldBe "id"
                        meta.getColumnType(1) shouldBe Types.INTEGER

                        // REQUIRED BINARY mit LogicalType STRING -> DuckDB VARCHAR.
                        meta.getColumnLabel(2) shouldBe "name"
                        meta.getColumnType(2) shouldBe Types.VARCHAR

                        // REQUIRED BOOLEAN -> DuckDB BOOLEAN.
                        meta.getColumnLabel(3) shouldBe "active"
                        meta.getColumnType(3) shouldBe Types.BOOLEAN
                    }
                }
            }
        } finally {
            Files.walk(tmpDir).use { stream ->
                stream.sorted(Comparator.reverseOrder())
                    .forEach { Files.deleteIfExists(it) }
            }
        }
    }
})
