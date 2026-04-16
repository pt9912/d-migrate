package dev.dmigrate.streaming.checkpoint

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §6): Vertragstests fuer das
 * Manifest-Grundmodell. Prueft die init-Validierungen, die spaetere Phasen
 * und Adapter als gegeben voraussetzen.
 */
class CheckpointManifestTest : FunSpec({

    val now: Instant = Instant.parse("2026-04-16T10:00:00Z")
    val later: Instant = Instant.parse("2026-04-16T10:05:00Z")

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
})
