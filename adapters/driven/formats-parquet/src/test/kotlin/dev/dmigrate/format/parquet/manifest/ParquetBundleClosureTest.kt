package dev.dmigrate.format.parquet.manifest

import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.data.BundleClosureContext
import dev.dmigrate.format.data.BundleClosureTable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ParquetBundleClosureTest : FunSpec({

    test("ParquetBundleClosure writes manifest.yaml with AP7 §5 fields") {
        val dir = Files.createTempDirectory("parquet-bundle-")
        try {
            val schemaUsers = ChunkSchema(
                table = "users",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("id", nullable = false, neutralType = NeutralType.BigInteger),
                    ChunkColumnSchema(
                        "rating",
                        nullable = true,
                        neutralType = NeutralType.Decimal(precision = 5, scale = 2),
                    ),
                ),
            )
            val schemaOrders = ChunkSchema(
                table = "orders",
                origin = SchemaOrigin.JDBC_METADATA,
                columns = listOf(
                    ChunkColumnSchema("total", nullable = false, neutralType = NeutralType.Float(FloatPrecision.DOUBLE)),
                ),
            )
            val usersFile = dir.resolve("users.parquet").also { Files.writeString(it, "users-bytes") }
            val ordersFile = dir.resolve("orders.parquet").also { Files.writeString(it, "orders-bytes") }
            val ctx = BundleClosureContext(
                directory = dir,
                format = DataExportFormat.PARQUET,
                tables = listOf(
                    BundleClosureTable(table = "users", file = usersFile, schema = schemaUsers, rowCount = 5),
                    BundleClosureTable(table = "orders", file = ordersFile, schema = schemaOrders, rowCount = 3),
                ),
            )
            val fixedClock = Clock.fixed(Instant.parse("2026-06-06T12:00:00Z"), ZoneOffset.UTC)
            val closure = ParquetBundleClosure(
                producerVersion = "0.9.8",
                manifestSha256 = true,
                clock = fixedClock,
            )

            closure(ctx)

            val manifestText = Files.readString(dir.resolve("manifest.yaml"))
            manifestText shouldContain "formatVersion:"
            manifestText shouldContain "1.0"
            manifestText shouldContain "producer: d-migrate"
            manifestText shouldContain "producerVersion: 0.9.8"
            manifestText shouldContain "exportedAt:"
            manifestText shouldContain "2026-06-06T12:00:00Z"
            manifestText shouldContain "schemaSource: jdbc-metadata"
            manifestText shouldContain "table: users"
            manifestText shouldContain "table: orders"
            manifestText shouldContain "file: users.parquet"
            manifestText shouldContain "file: orders.parquet"
            manifestText shouldContain "rowCount: 5"
            manifestText shouldContain "rowCount: 3"
            // SHA-256 — Lowercase Hex 64 Zeichen
            manifestText shouldMatch Regex("(?s).*sha256: [0-9a-f]{64}.*")
            // NeutralType-YAML
            manifestText shouldContain "kind: BigInteger"
            manifestText shouldContain "kind: Decimal"
            manifestText shouldContain "precision: 5"
            manifestText shouldContain "scale: 2"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ParquetBundleClosure ignores non-Parquet formats") {
        val dir = Files.createTempDirectory("parquet-bundle-no-op-")
        try {
            val ctx = BundleClosureContext(
                directory = dir,
                format = DataExportFormat.JSON,
                tables = emptyList(),
            )
            ParquetBundleClosure(producerVersion = "0.9.8")(ctx)
            Files.exists(dir.resolve("manifest.yaml")) shouldBe false
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ManifestSchemaSource maps MERGED to jdbc-metadata") {
        ManifestSchemaSource.fromSchemaOrigin(SchemaOrigin.MERGED) shouldBe
            ManifestSchemaSource.JDBC_METADATA
    }

    test("Sha256DigestCalculator produces lowercase hex 64 chars") {
        val tmp = Files.createTempFile("digest-test-", ".bin")
        try {
            Files.writeString(tmp, "hello d-migrate")
            val digest = Sha256DigestCalculator.compute(tmp)
            digest.length shouldBe 64
            digest shouldMatch Regex("[0-9a-f]{64}")
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("ChunkSchemaToManifest splits NeutralType variants into manifest columns") {
        val schema = ChunkSchema(
            table = "t",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(
                ChunkColumnSchema("bool_col", false, NeutralType.BooleanType),
                ChunkColumnSchema("int_col", false, NeutralType.Integer),
                ChunkColumnSchema("text_col", true, NeutralType.Text(maxLength = 50)),
                ChunkColumnSchema("ts_col", true, NeutralType.DateTime(timezone = true)),
            ),
        )
        val columns = ChunkSchemaToManifest.toManifestColumns(schema)
        columns.map { it.name } shouldContainAll listOf("bool_col", "int_col", "text_col", "ts_col")
        columns.first { it.name == "text_col" }.neutralType?.attributes?.get("maxLength") shouldBe 50
        columns.first { it.name == "ts_col" }.timezone shouldBe "UTC"
    }
})
