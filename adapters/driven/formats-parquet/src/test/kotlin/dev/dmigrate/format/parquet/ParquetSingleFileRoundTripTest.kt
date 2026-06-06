package dev.dmigrate.format.parquet

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestReader
import dev.dmigrate.format.parquet.manifest.ParquetSingleFileManifestWriter
import dev.dmigrate.format.parquet.manifest.Sha256DigestCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ParquetSingleFileRoundTripTest : FunSpec({

    test("ParquetSingleFileManifest Round-Trip — Footer-KV traegt ChunkSchema zurueck") {
        val tmp = Files.createTempFile("parquet-singlefile-rt-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val schema = ChunkSchema(
                table = "public.orders",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("id", false, NeutralType.BigInteger),
                    ChunkColumnSchema("name", true, NeutralType.Text(maxLength = 200)),
                    ChunkColumnSchema(
                        "total",
                        nullable = false,
                        neutralType = NeutralType.Decimal(precision = 12, scale = 2),
                    ),
                    ChunkColumnSchema("dob", true, NeutralType.Date),
                ),
            )
            val fixedClock = Clock.fixed(Instant.parse("2026-06-06T10:00:00Z"), ZoneOffset.UTC)
            val provider = ParquetSingleFileManifestWriter(
                producerVersion = "0.9.8",
                clock = fixedClock,
            ).provider

            Files.newOutputStream(tmp).use { out ->
                ParquetChunkWriter(out, extraMetaDataProvider = provider).use { writer ->
                    writer.begin("public.orders", schema)
                    writer.write(
                        DataChunk(
                            table = "public.orders",
                            columns = emptyList(),
                            rows = listOf(
                                arrayOf<Any?>(42L, "alice", BigDecimal("99.95"), LocalDate.of(1990, 5, 1)),
                            ),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }

            val preflight = ParquetSingleFilePreflight()
            val phase1 = preflight.phase1(tmp, explicitTable = null, computeContentSha256 = true)
            phase1.manifestPresent shouldBe true
            phase1.table shouldBe "public.orders"
            phase1.schema.columns.map { it.name } shouldBe listOf("id", "name", "total", "dob")
            phase1.schema.columns[2].neutralType shouldBe NeutralType.Decimal(12, 2)
            phase1.schema.columns[3].neutralType shouldBe NeutralType.Date
            phase1.contentSha256!!.length shouldBe 64
            phase1.contentSha256 shouldBe Sha256DigestCalculator.compute(tmp)

            // Phase 2 ohne Resume-Hash — Pass-Through.
            val phase2 = preflight.phase2(phase1)
            phase2.table shouldBe "public.orders"

            // Phase 2 mit korrektem Resume-Hash — Pass-Through.
            val phase2WithMatch = preflight.phase2(phase1, resumeExpectedSha256 = phase1.contentSha256)
            phase2WithMatch.table shouldBe "public.orders"

            // Phase 2 mit falschem Hash — wirft.
            val ex = shouldThrow<ParquetSingleFileResumeException> {
                preflight.phase2(phase1, resumeExpectedSha256 = "0".repeat(64))
            }
            ex.message!! shouldContain "PARQUET_SINGLE_FILE_CONTENT_CHANGED_SINCE_CHECKPOINT"
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("ParquetSingleFilePreflight ohne Footer-KV faellt auf Footer-MessageType") {
        val tmp = Files.createTempFile("parquet-no-manifest-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("id", false, NeutralType.Integer),
                    ChunkColumnSchema("name", true, NeutralType.Text()),
                ),
            )
            Files.newOutputStream(tmp).use { out ->
                ParquetChunkWriter(out).use { writer ->
                    writer.begin("users", schema)
                    writer.write(
                        DataChunk(
                            table = "users",
                            columns = emptyList(),
                            rows = listOf(arrayOf<Any?>(1, "alice")),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }
            val phase1 = ParquetSingleFilePreflight().phase1(tmp, explicitTable = "users")
            phase1.manifestPresent shouldBe false
            phase1.schema.origin shouldBe SchemaOrigin.MANIFEST_FALLBACK
            phase1.schema.columns.map { it.name } shouldBe listOf("id", "name")
            phase1.schema.columns.all { it.neutralType is NeutralType.Text } shouldBe true
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("Tabellennamens-Precedence — Mismatch wirft, ohne --table und KV wirft Required") {
        val tmp = Files.createTempFile("parquet-table-precedence-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(ChunkColumnSchema("id", false, NeutralType.Integer)),
            )
            val provider = ParquetSingleFileManifestWriter(producerVersion = "0.9.8").provider
            Files.newOutputStream(tmp).use { out ->
                ParquetChunkWriter(out, extraMetaDataProvider = provider).use { writer ->
                    writer.begin("users", schema)
                    writer.write(
                        DataChunk(
                            table = "users",
                            columns = emptyList(),
                            rows = listOf(arrayOf<Any?>(1)),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }
            // CLI --table matched manifest -> ok
            ParquetSingleFilePreflight().phase1(tmp, explicitTable = "users").table shouldBe "users"
            // CLI --table mismatch -> wirft
            val mismatchEx = shouldThrow<ParquetSingleFileTableMismatchException> {
                ParquetSingleFilePreflight().phase1(tmp, explicitTable = "orders")
            }
            mismatchEx.message!! shouldContain "PARQUET_SINGLE_FILE_TABLE_MISMATCH"

            // Tabelle aus Footer ohne --table -> ok (uebernommen)
            ParquetSingleFilePreflight().phase1(tmp, explicitTable = null).table shouldBe "users"
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("Tabellennamens-Required wirft ohne --table und ohne Footer-KV") {
        val tmp = Files.createTempFile("parquet-no-table-", ".parquet")
        Files.deleteIfExists(tmp)
        try {
            val schema = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(ChunkColumnSchema("id", false, NeutralType.Integer)),
            )
            Files.newOutputStream(tmp).use { out ->
                ParquetChunkWriter(out).use { writer ->
                    writer.begin("users", schema)
                    writer.write(
                        DataChunk(
                            table = "users",
                            columns = emptyList(),
                            rows = listOf(arrayOf<Any?>(1)),
                            chunkIndex = 0L,
                        )
                    )
                    writer.end()
                }
            }
            val ex = shouldThrow<ParquetSingleFileTableRequiredException> {
                ParquetSingleFilePreflight().phase1(tmp, explicitTable = null)
            }
            ex.message!! shouldContain "PARQUET_SINGLE_FILE_TABLE_REQUIRED"
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("ParquetSingleFileManifestReader liefert null bei fehlendem Key") {
        val reader = ParquetSingleFileManifestReader()
        reader.readSchema(emptyMap()) shouldBe null
        reader.readSchema(mapOf("other.key" to "value")) shouldBe null
    }
})
