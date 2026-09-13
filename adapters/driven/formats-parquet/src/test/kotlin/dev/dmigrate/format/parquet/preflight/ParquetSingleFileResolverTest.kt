package dev.dmigrate.format.parquet.preflight

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.ParquetSingleFileResumeException
import dev.dmigrate.format.parquet.ResolvedParquetSingleFile
import dev.dmigrate.streaming.ImportInput
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

/**
 * [ParquetSingleFileAdapter] und [ParquetSingleFileResolver] standen vor
 * diesem Test bei 0 % Zeilen-Deckung — nicht, weil sie ungetestet
 * gefaehrlich waeren (`ParquetSingleFilePreflight`, das dahinterliegt, ist
 * gut getestet), sondern weil niemand die Adapter-Schicht selbst je
 * aufgerufen hat. Der Verdacht aus dem Coverage-Gate-Befund bestaetigt
 * sich hier nicht: kein Hadoop-Hindernis, reine DTO-Uebersetzung und
 * duenne Orchestrierung.
 */
class ParquetSingleFileResolverTest : FunSpec({

    fun writeSingleFile(): java.nio.file.Path {
        val tmp = Files.createTempFile("parquet-single-resolver-", ".parquet")
        Files.deleteIfExists(tmp)
        val schema = ChunkSchema(
            table = "public.orders",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(
                ChunkColumnSchema("id", false, NeutralType.BigInteger),
                ChunkColumnSchema("name", true, NeutralType.Text(maxLength = 100)),
            ),
        )
        Files.newOutputStream(tmp).use { out ->
            ParquetChunkWriter(out).use { writer ->
                writer.begin("public.orders", schema)
                writer.write(
                    DataChunk(
                        table = "public.orders",
                        columns = emptyList(),
                        rows = listOf(arrayOf<Any?>(1L, "alice")),
                        chunkIndex = 0L,
                    ),
                )
                writer.end()
            }
        }
        return tmp
    }

    // ─── ParquetSingleFileAdapter: pure DTO-Uebersetzung ────────────

    test("toResolvedSingleFile uebersetzt alle Felder verlustfrei") {
        val phase = ResolvedParquetSingleFile(
            path = java.nio.file.Path.of("/tmp/x.parquet"),
            table = "orders",
            schema = ChunkSchema(table = "orders", origin = SchemaOrigin.MANIFEST_FALLBACK, columns = emptyList()),
            contentSha256 = "a".repeat(64),
            manifestPresent = true,
        )
        val translated = ParquetSingleFileAdapter.toResolvedSingleFile(phase)
        translated.table shouldBe phase.table
        translated.path shouldBe phase.path
        translated.schema shouldBe phase.schema
        translated.contentSha256 shouldBe phase.contentSha256
        translated.manifestPresent shouldBe phase.manifestPresent
    }

    test("toResolvedParquetSingleFile ist die Umkehrung — Rundlauf ist verlustfrei") {
        val phase = ResolvedParquetSingleFile(
            path = java.nio.file.Path.of("/tmp/y.parquet"),
            table = "users",
            schema = ChunkSchema(table = "users", origin = SchemaOrigin.JDBC_METADATA, columns = emptyList()),
            contentSha256 = null,
            manifestPresent = false,
        )
        val roundTripped = ParquetSingleFileAdapter.toResolvedParquetSingleFile(
            ParquetSingleFileAdapter.toResolvedSingleFile(phase),
        )
        roundTripped shouldBe phase
    }

    // ─── ParquetSingleFileResolver: Orchestrierung ueber echte Datei ──

    test("phase1 delegiert an das Preflight und liefert das Port-DTO") {
        val tmp = writeSingleFile()
        try {
            val resolved = ParquetSingleFileResolver().phase1(
                path = tmp,
                explicitTable = "public.orders",
                computeContentSha256 = true,
            )
            resolved.shouldBeInstanceOf<ImportInput.ResolvedSingleFile>()
            resolved.table shouldBe "public.orders"
            resolved.schema.columns.map { it.name } shouldBe listOf("id", "name")
            resolved.contentSha256!!.length shouldBe 64
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("phase2 ohne Resume-Hash ist Pass-Through (spart den adapter-internen Rundlauf)") {
        val tmp = writeSingleFile()
        try {
            val resolver = ParquetSingleFileResolver()
            val phase1 = resolver.phase1(tmp, explicitTable = "public.orders", computeContentSha256 = true)
            val phase2 = resolver.phase2(phase1, resumeExpectedSha256 = null)
            phase2 shouldBe phase1
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("phase2 mit passendem Resume-Hash liefert dasselbe Ergebnis nach Adapter-Rundlauf") {
        val tmp = writeSingleFile()
        try {
            val resolver = ParquetSingleFileResolver()
            val phase1 = resolver.phase1(tmp, explicitTable = "public.orders", computeContentSha256 = true)
            val phase2 = resolver.phase2(phase1, resumeExpectedSha256 = phase1.contentSha256)
            phase2.table shouldBe phase1.table
            phase2.schema shouldBe phase1.schema
            phase2.contentSha256 shouldBe phase1.contentSha256
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("phase2 mit falschem Resume-Hash wirft, ueber den Adapter hindurch") {
        val tmp = writeSingleFile()
        try {
            val resolver = ParquetSingleFileResolver()
            val phase1 = resolver.phase1(tmp, explicitTable = "public.orders", computeContentSha256 = true)
            shouldThrow<ParquetSingleFileResumeException> {
                resolver.phase2(phase1, resumeExpectedSha256 = "0".repeat(64))
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
})
