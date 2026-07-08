package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.DefaultValueDeserializer
import dev.dmigrate.format.data.ExportOptions
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.JdbcTypeHint
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.data.SeekableChunkSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.nio.file.Files
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class ParquetChunkRoundTripTest : FunSpec({

    test("ParquetChunkWriter + ParquetChunkReader round-trip mit gemischtem Schema") {
        val tempFile = Files.createTempFile("parquet-rt", ".parquet")
        Files.deleteIfExists(tempFile) // Writer erwartet leeren OutputStream
        try {
            val schema = ChunkSchema(
                table = "users",
                columns = listOf(
                    ChunkColumnSchema("id", nullable = false, neutralType = NeutralType.Integer),
                    ChunkColumnSchema("name", nullable = true, neutralType = NeutralType.Text()),
                    ChunkColumnSchema("active", nullable = false, neutralType = NeutralType.BooleanType),
                    ChunkColumnSchema(
                        "amount",
                        nullable = true,
                        neutralType = NeutralType.Decimal(precision = 10, scale = 2),
                    ),
                    ChunkColumnSchema("dob", nullable = true, neutralType = NeutralType.Date),
                    ChunkColumnSchema(
                        "registered_at",
                        nullable = false,
                        neutralType = NeutralType.DateTime(timezone = false),
                    ),
                ),
                origin = SchemaOrigin.JDBC_METADATA,
            )

            val factoryWriter = ParquetChunkWriterFactory()
            Files.newOutputStream(tempFile).use { output ->
                factoryWriter.create(DataExportFormat.PARQUET, output, ExportOptions()).use { writer ->
                    writer.begin("users", schema)
                    writer.write(
                        DataChunk(
                            table = "users",
                            columns = emptyList(),
                            rows = listOf(
                                arrayOf<Any?>(
                                    1,
                                    "alice",
                                    true,
                                    BigDecimal("123.45"),
                                    LocalDate.of(1990, 1, 15),
                                    Instant.parse("2024-03-04T12:34:56.789Z"),
                                ),
                                arrayOf<Any?>(
                                    2,
                                    null,
                                    false,
                                    null,
                                    null,
                                    Instant.parse("2024-03-05T01:23:45.123Z"),
                                ),
                            ),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }

            val factoryReader = ParquetSeekableDataChunkReaderFactory()
            val reader = factoryReader.create(
                format = DataExportFormat.PARQUET,
                source = SeekableChunkSource.Local(tempFile),
                table = "users",
                schema = schema,
                chunkSize = 10,
                options = FormatReadOptions(),
            )
            val chunk = reader.use { it.nextChunk() } ?: error("expected chunk, got null")
            chunk.rows.size shouldBe 2

            val row1 = chunk.rows[0]
            row1[0] shouldBe 1
            row1[1] shouldBe "alice"
            row1[2] shouldBe true
            (row1[3] as BigDecimal) shouldBe BigDecimal("123.45")
            row1[4] shouldBe LocalDate.of(1990, 1, 15)
            row1[5] shouldBe Instant.parse("2024-03-04T12:34:56.789Z")

            val row2 = chunk.rows[1]
            row2[0] shouldBe 2
            row2[1] shouldBe null
            row2[2] shouldBe false
            row2[3] shouldBe null
            row2[4] shouldBe null
            row2[5] shouldBe Instant.parse("2024-03-05T01:23:45.123Z")
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    test("Parquet timestamp columns import through ValueDeserializer without Instant type error (I-10)") {
        val tempFile = Files.createTempFile("parquet-ts-i10", ".parquet")
        Files.deleteIfExists(tempFile)
        try {
            val schema = ChunkSchema(
                table = "events",
                columns = listOf(
                    ChunkColumnSchema(
                        "created_at", nullable = false, neutralType = NeutralType.DateTime(timezone = false),
                    ),
                    ChunkColumnSchema(
                        "occurred_at", nullable = false, neutralType = NeutralType.DateTime(timezone = true),
                    ),
                ),
                origin = SchemaOrigin.JDBC_METADATA,
            )
            val tsUtc = Instant.parse("2024-03-04T12:34:56Z")
            val tstzUtc = Instant.parse("2024-03-04T10:34:56Z")

            Files.newOutputStream(tempFile).use { output ->
                ParquetChunkWriterFactory().create(DataExportFormat.PARQUET, output, ExportOptions()).use { writer ->
                    writer.begin("events", schema)
                    writer.write(
                        DataChunk(
                            table = "events",
                            columns = emptyList(),
                            rows = listOf(arrayOf<Any?>(tsUtc, tstzUtc)),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }

            val chunk = ParquetSeekableDataChunkReaderFactory().create(
                format = DataExportFormat.PARQUET,
                source = SeekableChunkSource.Local(tempFile),
                table = "events",
                schema = schema,
                chunkSize = 10,
                options = FormatReadOptions(),
            ).use { it.nextChunk() } ?: error("expected chunk, got null")

            // Der Reader liefert Instant (Parquet INT64 µs) — vor I-10 brach der Import
            // hier mit "expects TIMESTAMP, got Instant" ab.
            val row = chunk.rows[0]
            row[0].shouldBeInstanceOf<Instant>()
            row[1].shouldBeInstanceOf<Instant>()

            val hints = mapOf(
                "created_at" to JdbcTypeHint(Types.TIMESTAMP),
                "occurred_at" to JdbcTypeHint(Types.TIMESTAMP_WITH_TIMEZONE),
            )
            val deserializer = DefaultValueDeserializer(typeHintOf = { hints[it] })

            deserializer.deserialize("events", "created_at", row[0]) shouldBe
                LocalDateTime.ofInstant(tsUtc, ZoneOffset.UTC)
            deserializer.deserialize("events", "occurred_at", row[1]) shouldBe
                tstzUtc.atOffset(ZoneOffset.UTC)
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    test("ParquetChunkReader wirft BUNDLE_SCHEMA_PARQUET_MISMATCH bei Spaltenanzahl-Drift") {
        val tempFile = Files.createTempFile("parquet-rt-mismatch", ".parquet")
        Files.deleteIfExists(tempFile)
        try {
            val writeSchema = ChunkSchema(
                table = "t", origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("a", nullable = false, neutralType = NeutralType.Integer),
                ),
            )
            Files.newOutputStream(tempFile).use { output ->
                ParquetChunkWriter(output).use { writer ->
                    writer.begin("t", writeSchema)
                    writer.write(
                        DataChunk(
                            table = "t",
                            columns = emptyList(),
                            rows = listOf(arrayOf<Any?>(1)),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }

            val mismatchedSchema = writeSchema.copy(
                columns = writeSchema.columns + ChunkColumnSchema(
                    "b", nullable = true, neutralType = NeutralType.Text(),
                ),
            )

            val ex = shouldThrow<ParquetSchemaMismatchException> {
                ParquetChunkReader(schema = mismatchedSchema, file = tempFile, chunkSize = 10)
            }
            ex.message!!.let {
                check(it.contains("BUNDLE_SCHEMA_PARQUET_MISMATCH"))
                check(it.contains("table=t"))
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    test("DefaultDataChunkReader/WriterFactory werfen Contract-Branch fuer PARQUET") {
        val readerFactory = dev.dmigrate.format.data.DefaultDataChunkReaderFactory()
        val writerFactory = dev.dmigrate.format.data.DefaultDataChunkWriterFactory()

        shouldThrow<IllegalStateException> {
            readerFactory.create(
                format = DataExportFormat.PARQUET,
                input = java.io.ByteArrayInputStream(ByteArray(0)),
                table = "x",
                chunkSize = 1,
                options = FormatReadOptions(),
            )
        }.message!! shouldBe (
            "DefaultDataChunkReaderFactory does not support Parquet; " +
                "Parquet reads go through StreamingImporter's seekableReaderFactory " +
                "(ParquetSeekableDataChunkReaderFactory)"
        )

        shouldThrow<IllegalStateException> {
            writerFactory.create(
                format = DataExportFormat.PARQUET,
                output = java.io.ByteArrayOutputStream(),
                options = ExportOptions(),
            )
        }.message!! shouldBe (
            "DefaultDataChunkWriterFactory does not support Parquet; " +
                "use ParquetChunkWriterFactory via the CLI CompositeDataChunkWriterFactory"
        )
    }
})
