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

class ImportInputResolverResolvedBundleTest : FunSpec({

    test("ResolvedBundle wird auf List<ResolvedTableInput.Seekable> abgebildet") {
        val schema = ChunkSchema(
            table = "users",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(ChunkColumnSchema("id", false, NeutralType.Integer)),
        )
        val bundle = ImportInput.ResolvedBundle(
            bundleRoot = Path.of("/tmp/bundle"),
            tables = listOf(
                ResolvedBundleTableBinding(
                    table = "users",
                    path = Path.of("/tmp/bundle/users.parquet"),
                    schema = schema,
                    expectedSha256 = null,
                ),
                ResolvedBundleTableBinding(
                    table = "orders",
                    path = Path.of("/tmp/bundle/orders.parquet"),
                    schema = schema.copy(table = "orders"),
                    expectedSha256 = "abc",
                ),
            ),
            resumeFingerprint = BundleResumeFingerprint("h", "1.0", "0.9.8", listOf("users", "orders")),
        )
        val resolved = ImportInputResolver().resolve(bundle, DataExportFormat.PARQUET)
        resolved.size shouldBe 2
        resolved.all { it is ResolvedTableInput.Seekable } shouldBe true
        val first = resolved[0] as ResolvedTableInput.Seekable
        first.table shouldBe "users"
        (first.source as SeekableChunkSource.Local).path shouldBe Path.of("/tmp/bundle/users.parquet")
        first.schema shouldBe schema

        val second = resolved[1] as ResolvedTableInput.Seekable
        second.table shouldBe "orders"
        (second.source as SeekableChunkSource.Local).path shouldBe Path.of("/tmp/bundle/orders.parquet")
    }
})
