package dev.dmigrate.streaming

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.data.SeekableChunkSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ImportInputResolverResolvedSingleFileTest : FunSpec({

    test("ResolvedSingleFile wird auf eine Seekable-Liste mit einem Element abgebildet") {
        val schema = ChunkSchema(
            table = "orders",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(
                ChunkColumnSchema("id", false, NeutralType.BigInteger),
                ChunkColumnSchema("amount", true, NeutralType.Decimal(10, 2)),
            ),
        )
        val singleFile = ImportInput.ResolvedSingleFile(
            table = "orders",
            path = Path.of("/tmp/orders.parquet"),
            schema = schema,
            contentSha256 = "deadbeef".padEnd(64, '0'),
        )

        val resolved = ImportInputResolver().resolve(singleFile, DataExportFormat.PARQUET)
        resolved.size shouldBe 1
        val first = resolved[0] as ResolvedTableInput.Seekable
        first.table shouldBe "orders"
        (first.source as SeekableChunkSource.Local).path shouldBe Path.of("/tmp/orders.parquet")
        first.schema shouldBe schema
    }
})
