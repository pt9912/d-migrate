package dev.dmigrate.streaming.checkpoint

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §6): Vertragstests fuer den
 * dateibasierten [FileCheckpointStore]. Phase B prueft vor allem:
 *
 * - Roundtrip: gespeichertes Manifest laesst sich wieder laden und hat
 *   dieselben Felder
 * - atomares Schreiben (§4.6): partielle Dateien werden nicht zur
 *   gueltigen Resume-Quelle
 * - Version-Validation (§4.2): inkompatible `schemaVersion` wirft
 *   [UnsupportedCheckpointVersionException]
 * - klare Fehler fuer korrupte Dateien (CheckpointStoreException)
 * - `list()` ist tolerant gegenueber fremden Dateien im Verzeichnis
 */
class FileCheckpointStoreTest : FunSpec({

    fun sampleManifest(operationId: String = "op-123") = CheckpointManifest(
        operationId = operationId,
        operationType = CheckpointOperationType.EXPORT,
        createdAt = Instant.parse("2026-04-16T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-16T10:05:00Z"),
        format = "json",
        chunkSize = 10_000,
        tableSlices = listOf(
            CheckpointTableSlice(
                table = "users",
                status = CheckpointSliceStatus.IN_PROGRESS,
                rowsProcessed = 5_000L,
                chunksProcessed = 5L,
                lastMarker = "id:1000",
            ),
            CheckpointTableSlice(
                table = "orders",
                status = CheckpointSliceStatus.PENDING,
            ),
        ),
        optionsFingerprint = "abc123",
    )

    test("save then load roundtrips all Phase-B manifest fields") {
        val dir = Files.createTempDirectory("dmigrate-cp-roundtrip-")
        try {
            val store = FileCheckpointStore(dir)
            val original = sampleManifest()
            store.save(original)

            val loaded = store.load(original.operationId)
            loaded.shouldNotBeNull()
            loaded shouldBe original
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load returns null when no manifest exists for operationId") {
        val dir = Files.createTempDirectory("dmigrate-cp-missing-")
        try {
            val store = FileCheckpointStore(dir)
            store.load("nonexistent-op").shouldBeNull()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("save is atomic: no temp file remains after success") {
        val dir = Files.createTempDirectory("dmigrate-cp-atomic-")
        try {
            val store = FileCheckpointStore(dir)
            store.save(sampleManifest("op-atomic"))
            val leftovers = Files.list(dir).use { s ->
                s.toList().filter { it.fileName.toString().endsWith(".tmp") }
            }
            leftovers shouldBe emptyList()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("save overwrites existing manifest with the new content") {
        val dir = Files.createTempDirectory("dmigrate-cp-overwrite-")
        try {
            val store = FileCheckpointStore(dir)
            store.save(sampleManifest("op-overwrite"))
            val updated = sampleManifest("op-overwrite").copy(
                updatedAt = Instant.parse("2026-04-16T11:00:00Z"),
                tableSlices = listOf(
                    CheckpointTableSlice(
                        table = "users",
                        status = CheckpointSliceStatus.COMPLETED,
                        rowsProcessed = 12_345L,
                        chunksProcessed = 12L,
                    ),
                ),
            )
            store.save(updated)

            val loaded = store.load("op-overwrite")
            loaded shouldBe updated
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects unsupported schemaVersion with UnsupportedCheckpointVersionException") {
        val dir = Files.createTempDirectory("dmigrate-cp-ver-")
        try {
            Files.writeString(
                dir.resolve("op-old${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 99
                operationId: op-old
                operationType: EXPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                chunkSize: 10000
                tableSlices: []
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<UnsupportedCheckpointVersionException> { store.load("op-old") }
            ex.foundVersion shouldBe 99
            ex.supportedVersion shouldBe CheckpointManifest.CURRENT_SCHEMA_VERSION
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load raises CheckpointStoreException for malformed YAML") {
        val dir = Files.createTempDirectory("dmigrate-cp-broken-")
        try {
            Files.writeString(
                dir.resolve("op-broken${FileCheckpointStore.MANIFEST_SUFFIX}"),
                "{[ not yaml",
            )
            val store = FileCheckpointStore(dir)
            shouldThrow<CheckpointStoreException> { store.load("op-broken") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("list returns saved manifests and ignores foreign files") {
        val dir = Files.createTempDirectory("dmigrate-cp-list-")
        try {
            val store = FileCheckpointStore(dir)
            store.save(sampleManifest("op-a"))
            store.save(sampleManifest("op-b").copy(
                operationType = CheckpointOperationType.IMPORT,
            ))
            // Fremddatei, die list() nicht verwirren darf
            Files.writeString(dir.resolve("README.txt"), "unrelated")

            val refs = store.list().sortedBy { it.operationId }
            refs.map { it.operationId } shouldContainExactly listOf("op-a", "op-b")
            refs.single { it.operationId == "op-a" }.operationType shouldBe CheckpointOperationType.EXPORT
            refs.single { it.operationId == "op-b" }.operationType shouldBe CheckpointOperationType.IMPORT
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("complete removes the manifest for the given operationId") {
        val dir = Files.createTempDirectory("dmigrate-cp-complete-")
        try {
            val store = FileCheckpointStore(dir)
            store.save(sampleManifest("op-complete"))
            store.complete("op-complete")
            store.load("op-complete").shouldBeNull()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("complete on unknown operationId is a no-op") {
        val dir = Files.createTempDirectory("dmigrate-cp-complete-missing-")
        try {
            val store = FileCheckpointStore(dir)
            store.complete("never-existed") // must not throw
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects blank operationId") {
        val dir = Files.createTempDirectory("dmigrate-cp-blank-")
        try {
            val store = FileCheckpointStore(dir)
            shouldThrow<IllegalArgumentException> { store.load("") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects manifest with missing operationId field") {
        val dir = Files.createTempDirectory("dmigrate-cp-missing-opid-")
        try {
            Files.writeString(
                dir.resolve("op-x${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 1
                operationType: EXPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                chunkSize: 10000
                tableSlices: []
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-x") }
            ex.message!!.contains("operationId") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects manifest with missing schemaVersion") {
        val dir = Files.createTempDirectory("dmigrate-cp-missing-ver-")
        try {
            Files.writeString(
                dir.resolve("op-y${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                operationId: op-y
                operationType: EXPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                chunkSize: 10000
                tableSlices: []
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-y") }
            ex.message!!.contains("schemaVersion") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects manifest with invalid operationType") {
        val dir = Files.createTempDirectory("dmigrate-cp-bad-optype-")
        try {
            Files.writeString(
                dir.resolve("op-z${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 1
                operationId: op-z
                operationType: BOGUS
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                chunkSize: 10000
                tableSlices: []
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-z") }
            ex.message!!.contains("operationType") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects manifest with non-ISO createdAt") {
        val dir = Files.createTempDirectory("dmigrate-cp-bad-inst-")
        try {
            Files.writeString(
                dir.resolve("op-ts${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 1
                operationId: op-ts
                operationType: EXPORT
                createdAt: yesterday
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                chunkSize: 10000
                tableSlices: []
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-ts") }
            ex.message!!.contains("createdAt") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects manifest with missing chunkSize") {
        val dir = Files.createTempDirectory("dmigrate-cp-nocs-")
        try {
            Files.writeString(
                dir.resolve("op-cs${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 1
                operationId: op-cs
                operationType: EXPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                tableSlices: []
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            shouldThrow<CheckpointStoreException> { store.load("op-cs") }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects manifest with invalid slice status") {
        val dir = Files.createTempDirectory("dmigrate-cp-badslice-")
        try {
            Files.writeString(
                dir.resolve("op-sl${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 1
                operationId: op-sl
                operationType: EXPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: json
                chunkSize: 10000
                tableSlices:
                  - table: users
                    status: BOGUS_STATE
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-sl") }
            ex.message!!.contains("status") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load returns empty tableSlices when key absent") {
        val dir = Files.createTempDirectory("dmigrate-cp-nosl-")
        try {
            Files.writeString(
                dir.resolve("op-ns${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 1
                operationId: op-ns
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: yaml
                chunkSize: 5000
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val loaded = store.load("op-ns")
            loaded.shouldNotBeNull()
            loaded.tableSlices shouldBe emptyList()
            loaded.operationType shouldBe CheckpointOperationType.IMPORT
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("list() on non-existent directory returns empty list") {
        val base = Files.createTempDirectory("dmigrate-cp-empty-")
        try {
            val notYet = base.resolve("not-created-yet")
            val store = FileCheckpointStore(notYet)
            store.list() shouldBe emptyList()
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    test("save creates the directory when it does not exist") {
        val base = Files.createTempDirectory("dmigrate-cp-mkdir-")
        try {
            val nested = base.resolve("nested/level2")
            val store = FileCheckpointStore(nested)
            store.save(sampleManifest("op-mk"))
            Files.isDirectory(nested) shouldBe true
            store.load("op-mk").shouldNotBeNull()
        } finally {
            base.toFile().deleteRecursively()
        }
    }
})
