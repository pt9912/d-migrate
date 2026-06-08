package dev.dmigrate.format.parquet.preflight

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.parquet.ParquetChunkWriter
import dev.dmigrate.format.parquet.manifest.ParquetBundleClosure
import dev.dmigrate.streaming.BundleClosureContext
import dev.dmigrate.streaming.BundleClosureTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ParquetBundleResolverTest : FunSpec({

    fun writeBundle(dir: Path, sha256: Boolean): Pair<ChunkSchema, ChunkSchema> {
        val usersSchema = ChunkSchema(
            table = "users",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(
                ChunkColumnSchema("id", false, NeutralType.BigInteger),
                ChunkColumnSchema("name", true, NeutralType.Text()),
            ),
        )
        val ordersSchema = ChunkSchema(
            table = "orders",
            origin = SchemaOrigin.JDBC_METADATA,
            columns = listOf(
                ChunkColumnSchema("order_id", false, NeutralType.BigInteger),
            ),
        )
        Files.newOutputStream(dir.resolve("users.parquet")).use { out ->
            ParquetChunkWriter(out).use { writer ->
                writer.begin("users", usersSchema)
                writer.write(
                    DataChunk(
                        table = "users", columns = emptyList(),
                        rows = listOf(arrayOf<Any?>(1L, "alice")),
                        chunkIndex = 0L,
                    )
                )
                writer.end()
            }
        }
        Files.newOutputStream(dir.resolve("orders.parquet")).use { out ->
            ParquetChunkWriter(out).use { writer ->
                writer.begin("orders", ordersSchema)
                writer.write(
                    DataChunk(
                        table = "orders", columns = emptyList(),
                        rows = listOf(arrayOf<Any?>(10L)),
                        chunkIndex = 0L,
                    )
                )
                writer.end()
            }
        }
        val fixedClock = Clock.fixed(Instant.parse("2026-06-06T11:00:00Z"), ZoneOffset.UTC)
        ParquetBundleClosure(
            producerVersion = "0.9.8",
            manifestSha256 = sha256,
            clock = fixedClock,
        )(
            BundleClosureContext(
                directory = dir,
                format = DataExportFormat.PARQUET,
                tables = listOf(
                    BundleClosureTable("users", dir.resolve("users.parquet"), usersSchema, rowCount = 1),
                    BundleClosureTable("orders", dir.resolve("orders.parquet"), ordersSchema, rowCount = 1),
                ),
            )
        )
        return usersSchema to ordersSchema
    }

    test("ParquetBundleResolver — Round-Trip von ParquetBundleClosure-Output") {
        val dir = Files.createTempDirectory("parquet-bundle-rt-")
        try {
            val (usersSchema, ordersSchema) = writeBundle(dir, sha256 = true)
            val bundle = ParquetBundleResolver().resolve(bundleRoot = dir)
            bundle.tables.map { it.table } shouldContainExactly listOf("users", "orders")
            bundle.tables[0].schema.columns.map { it.name } shouldBe listOf("id", "name")
            bundle.tables[0].schema.columns[0].neutralType shouldBe NeutralType.BigInteger
            bundle.tables[1].schema.columns.map { it.name } shouldBe listOf("order_id")
            bundle.bundleRoot shouldBe dir.toAbsolutePath().normalize()
            bundle.resumeFingerprint.formatVersion shouldBe "1.0"
            bundle.resumeFingerprint.producerVersion shouldBe "0.9.8"
            bundle.resumeFingerprint.tableOrder shouldBe listOf("users", "orders")
            bundle.tables.all { it.expectedSha256?.length == 64 } shouldBe true
            // Schemas selbst sind unveraendert (Round-Trip)
            bundle.tables[0].schema shouldBe usersSchema
            bundle.tables[1].schema shouldBe ordersSchema
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ParquetBundlePreflight wirft MANIFEST_NOT_FOUND wenn manifest.yaml fehlt") {
        val dir = Files.createTempDirectory("parquet-no-manifest-")
        try {
            Files.writeString(dir.resolve("users.parquet"), "fake")
            val ex = shouldThrow<ParquetBundlePreflightException> {
                ParquetBundleResolver().resolve(bundleRoot = dir)
            }
            ex.message!! shouldContain "MANIFEST_NOT_FOUND"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ParquetBundlePreflight wirft MANIFEST_FILE_UNREFERENCED bei Orphan-Datei") {
        val dir = Files.createTempDirectory("parquet-orphan-")
        try {
            writeBundle(dir, sha256 = false)
            // Orphan-Datei hinzufuegen
            Files.writeString(dir.resolve("orphan.parquet"), "fake")
            val ex = shouldThrow<ParquetBundlePreflightException> {
                ParquetBundleResolver().resolve(bundleRoot = dir)
            }
            ex.message!! shouldContain "MANIFEST_FILE_UNREFERENCED"
            ex.message!! shouldContain "orphan.parquet"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ParquetBundlePreflight wirft MANIFEST_SHA256_MISMATCH bei manipulierter Datei") {
        val dir = Files.createTempDirectory("parquet-sha-mismatch-")
        try {
            writeBundle(dir, sha256 = true)
            // Users-Datei nach Manifest manipulieren
            Files.write(dir.resolve("users.parquet"), ByteArray(8))
            val ex = shouldThrow<ParquetBundlePreflightException> {
                ParquetBundleResolver().resolve(bundleRoot = dir)
            }
            ex.message!! shouldContain "MANIFEST_SHA256_MISMATCH"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("verifyContentSha256 = false ueberspringt Per-Tabelle-SHA-Vergleich (--no-checkpoint)") {
        // S8e (AP12 §7.1): der Bundle-`--no-checkpoint`-Pfad reicht
        // verifyContentSha256 = false durch (Hook: verifyContentSha256 =
        // computeContentSha256). Eine nach dem Manifest manipulierte Datei
        // darf dann weder einen MANIFEST_SHA256_MISMATCH werfen noch einen
        // Re-Compute ausloesen — der gesamte if(verifyContentSha256)-Block
        // wird uebersprungen (Spiegel zum MANIFEST_SHA256_MISMATCH-Test oben).
        val dir = Files.createTempDirectory("parquet-nocp-skip-sha-")
        try {
            writeBundle(dir, sha256 = true)
            // Users-Datei nach Manifest manipulieren — mit aktivem Hash-Check
            // (Default true) wuerde das MANIFEST_SHA256_MISMATCH werfen.
            Files.write(dir.resolve("users.parquet"), ByteArray(8))

            val bundle = ParquetBundleResolver().resolve(
                bundleRoot = dir,
                verifyContentSha256 = false,
            )

            // Kein Throw; Bundle loest normal auf. expectedSha256 bleibt aus
            // dem Manifest erhalten (nur der Live-Vergleich entfaellt).
            bundle.tables.map { it.table } shouldContainExactly listOf("users", "orders")
            bundle.tables.all { it.expectedSha256?.length == 64 } shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("tableFilter wendet sich auf Bundle an") {
        val dir = Files.createTempDirectory("parquet-tablefilter-")
        try {
            writeBundle(dir, sha256 = false)
            val bundle = ParquetBundleResolver().resolve(
                bundleRoot = dir,
                tableFilter = listOf("orders"),
            )
            bundle.tables.map { it.table } shouldBe listOf("orders")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("tableOrder wendet sich auf Bundle an") {
        val dir = Files.createTempDirectory("parquet-tableorder-")
        try {
            writeBundle(dir, sha256 = false)
            val bundle = ParquetBundleResolver().resolve(
                bundleRoot = dir,
                tableOrder = listOf("orders", "users"),
            )
            bundle.tables.map { it.table } shouldBe listOf("orders", "users")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
