package dev.dmigrate.streaming.checkpoint

import dev.dmigrate.streaming.BundleResumeFingerprint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * LF-013 / LN-012 / LN-013: Vertragstests fuer das Manifest-Grundmodell.
 * Prueft die init-Validierungen, die Adapter als gegeben voraussetzen.
 */
class CheckpointManifestTest : FunSpec({

    val now: Instant = Instant.parse("2026-04-16T10:00:00Z")
    val later: Instant = Instant.parse("2026-04-16T10:05:00Z")

    // SHA-256 des leeren Bytestroms — 64 Hex-Zeichen, als gueltiger
    // contentSha256/manifestSha256-Wert wiederverwendet.
    val validSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun minimal() = CheckpointManifest(
        operationId = "op-1",
        operationType = CheckpointOperationType.EXPORT,
        createdAt = now,
        updatedAt = now,
        format = "json",
        chunkSize = 10_000,
    )

    test("default schemaVersion is CURRENT_SCHEMA_VERSION") {
        minimal().schemaVersion shouldBe CheckpointManifest.CURRENT_SCHEMA_VERSION
    }

    test("blank operationId is rejected") {
        shouldThrow<IllegalArgumentException> {
            minimal().copy(operationId = "")
        }
    }

    test("non-positive chunkSize is rejected") {
        shouldThrow<IllegalArgumentException> { minimal().copy(chunkSize = 0) }
        shouldThrow<IllegalArgumentException> { minimal().copy(chunkSize = -1) }
    }

    test("updatedAt before createdAt is rejected") {
        shouldThrow<IllegalArgumentException> {
            minimal().copy(createdAt = later, updatedAt = now)
        }
    }

    test("schemaVersion < 1 is rejected") {
        shouldThrow<IllegalArgumentException> {
            minimal().copy(schemaVersion = 0)
        }
    }

    test("CheckpointTableSlice rejects blank table name") {
        shouldThrow<IllegalArgumentException> {
            CheckpointTableSlice(
                table = "",
                status = CheckpointSliceStatus.PENDING,
            )
        }
    }

    test("CheckpointTableSlice rejects negative counters") {
        shouldThrow<IllegalArgumentException> {
            CheckpointTableSlice(
                table = "t",
                status = CheckpointSliceStatus.PENDING,
                rowsProcessed = -1,
            )
        }
        shouldThrow<IllegalArgumentException> {
            CheckpointTableSlice(
                table = "t",
                status = CheckpointSliceStatus.PENDING,
                chunksProcessed = -1,
            )
        }
    }

    test("UnsupportedCheckpointVersionException carries found and supported versions") {
        val ex = UnsupportedCheckpointVersionException(foundVersion = 42)
        ex.foundVersion shouldBe 42
        ex.supportedVersion shouldBe CheckpointManifest.CURRENT_SCHEMA_VERSION
        ex.message!! shouldBe "Checkpoint manifest schemaVersion=42 is not supported " +
            "by this build (supported: ${CheckpointManifest.CURRENT_SCHEMA_VERSION})."
    }

    test("SingleFileCheckpointSpecifics exposes the parquet-single-file discriminator") {
        val specifics = SingleFileCheckpointSpecifics(
            contentSha256 = validSha256,
            table = "users",
        )
        specifics.contentSha256 shouldBe validSha256
        specifics.table shouldBe "users"
        specifics.bundleKind shouldBe SingleFileCheckpointSpecifics.BUNDLE_KIND
        SingleFileCheckpointSpecifics.BUNDLE_KIND shouldBe "parquet-single-file"
    }

    test("SingleFileCheckpointSpecifics rejects a contentSha256 that is not 64 hex chars") {
        // zu kurz
        shouldThrow<IllegalArgumentException> {
            SingleFileCheckpointSpecifics(contentSha256 = "deadbeef", table = "users")
        }
        // zu lang
        shouldThrow<IllegalArgumentException> {
            SingleFileCheckpointSpecifics(contentSha256 = validSha256 + "00", table = "users")
        }
    }

    test("SingleFileCheckpointSpecifics rejects a blank table") {
        shouldThrow<IllegalArgumentException> {
            SingleFileCheckpointSpecifics(contentSha256 = validSha256, table = " ")
        }
    }

    test("BundleCheckpointSpecifics exposes the parquet-bundle discriminator") {
        val specifics = BundleCheckpointSpecifics(
            fingerprint = BundleResumeFingerprint(
                manifestSha256 = validSha256,
                formatVersion = "1.0",
                producerVersion = "0.9.9",
                tableOrder = listOf("users", "orders"),
            ),
        )
        specifics.fingerprint.tableOrder shouldBe listOf("users", "orders")
        specifics.fingerprint.manifestSha256 shouldBe validSha256
        specifics.bundleKind shouldBe BundleCheckpointSpecifics.BUNDLE_KIND
        BundleCheckpointSpecifics.BUNDLE_KIND shouldBe "parquet-bundle"
    }
})
