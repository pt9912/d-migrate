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

    // ─── 0.9.0 Phase C.2: CheckpointResumePosition roundtrip ──────
    // (`docs/ImpPlan-0.9.0-C2.md` §5.2)
    // ──────────────────────────────────────────────────────────────

    test("C.2: save + load roundtrips a slice with resumePosition + tie-breakers") {
        val dir = Files.createTempDirectory("dmigrate-cp-c2-")
        try {
            val original = CheckpointManifest(
                operationId = "op-c2",
                operationType = CheckpointOperationType.EXPORT,
                createdAt = Instant.parse("2026-04-16T10:00:00Z"),
                updatedAt = Instant.parse("2026-04-16T10:05:00Z"),
                format = "json",
                chunkSize = 10_000,
                tableSlices = listOf(
                    CheckpointTableSlice(
                        table = "users",
                        status = CheckpointSliceStatus.IN_PROGRESS,
                        rowsProcessed = 100, chunksProcessed = 1,
                        resumePosition = CheckpointResumePosition(
                            markerColumn = "updated_at",
                            markerValue = "2026-04-01",
                            tieBreakerColumns = listOf("tenant", "id"),
                            tieBreakerValues = listOf("acme", "42"),
                        ),
                    ),
                ),
            )
            val store = FileCheckpointStore(dir)
            store.save(original)
            val loaded = store.load("op-c2")
            loaded.shouldNotBeNull()
            loaded shouldBe original
            val slice = loaded.tableSlices.single()
            slice.resumePosition.shouldNotBeNull()
            slice.resumePosition!!.markerColumn shouldBe "updated_at"
            slice.resumePosition!!.markerValue shouldBe "2026-04-01"
            slice.resumePosition!!.tieBreakerColumns shouldContainExactly listOf("tenant", "id")
            slice.resumePosition!!.tieBreakerValues shouldContainExactly listOf<String?>("acme", "42")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("C.2: resumePosition is omitted when null (backward compatibility with Phase-B manifests)") {
        val dir = Files.createTempDirectory("dmigrate-cp-c2-null-")
        try {
            val original = sampleManifest("op-no-c2")
            val store = FileCheckpointStore(dir)
            store.save(original)
            val loaded = store.load("op-no-c2")
            loaded.shouldNotBeNull()
            // No resumePosition written for any slice
            loaded.tableSlices.forEach { it.resumePosition shouldBe null }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("C.2: load tolerates missing resumePosition field in pre-C.2 manifest files") {
        val dir = Files.createTempDirectory("dmigrate-cp-c2-legacy-")
        try {
            val legacy = """
                schemaVersion: 1
                operationId: op-legacy
                operationType: EXPORT
                createdAt: "2026-04-16T10:00:00Z"
                updatedAt: "2026-04-16T10:00:00Z"
                format: json
                chunkSize: 10000
                optionsFingerprint: "abc"
                tableSlices:
                  - table: users
                    status: IN_PROGRESS
                    rowsProcessed: 5
                    chunksProcessed: 1
                    lastMarker: "id:42"
            """.trimIndent()
            Files.writeString(dir.resolve("op-legacy.checkpoint.yaml"), legacy)
            val store = FileCheckpointStore(dir)
            val loaded = store.load("op-legacy")
            loaded.shouldNotBeNull()
            loaded.tableSlices.single().resumePosition shouldBe null
            loaded.tableSlices.single().lastMarker shouldBe "id:42"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("C.2: load rejects resumePosition missing markerColumn") {
        val dir = Files.createTempDirectory("dmigrate-cp-c2-invalid-mc-")
        try {
            val yaml = """
                schemaVersion: 1
                operationId: op-invalid
                operationType: EXPORT
                createdAt: "2026-04-16T10:00:00Z"
                updatedAt: "2026-04-16T10:00:00Z"
                format: json
                chunkSize: 10000
                tableSlices:
                  - table: users
                    status: IN_PROGRESS
                    resumePosition:
                      markerValue: "2026-04-01"
                      tieBreakerColumns: []
                      tieBreakerValues: []
            """.trimIndent()
            Files.writeString(dir.resolve("op-invalid.checkpoint.yaml"), yaml)
            val ex = shouldThrow<CheckpointStoreException> {
                FileCheckpointStore(dir).load("op-invalid")
            }
            ex.message!!.contains("markerColumn") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("C.2: load rejects resumePosition with mismatched tie-breaker sizes") {
        val dir = Files.createTempDirectory("dmigrate-cp-c2-mismatch-")
        try {
            val yaml = """
                schemaVersion: 1
                operationId: op-mismatch
                operationType: EXPORT
                createdAt: "2026-04-16T10:00:00Z"
                updatedAt: "2026-04-16T10:00:00Z"
                format: json
                chunkSize: 10000
                tableSlices:
                  - table: users
                    status: IN_PROGRESS
                    resumePosition:
                      markerColumn: updated_at
                      markerValue: "2026-04-01"
                      tieBreakerColumns: ["tenant", "id"]
                      tieBreakerValues: ["acme"]
            """.trimIndent()
            Files.writeString(dir.resolve("op-mismatch.checkpoint.yaml"), yaml)
            val ex = shouldThrow<CheckpointStoreException> {
                FileCheckpointStore(dir).load("op-mismatch")
            }
            ex.message!!.contains("resumePosition") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("C.2: save + load roundtrips a resumePosition with null markerValue") {
        // Rather than testing raw YAML null-literal parsing semantics
        // (which SnakeYAML-engine handles internally), we use the
        // save-path to produce a manifest whose `markerValue` is null,
        // then reload it — both ends are owned by the store.
        val dir = Files.createTempDirectory("dmigrate-cp-c2-nullmarker-")
        try {
            val store = FileCheckpointStore(dir)
            val original = CheckpointManifest(
                operationId = "op-nullmarker",
                operationType = CheckpointOperationType.EXPORT,
                createdAt = Instant.parse("2026-04-16T10:00:00Z"),
                updatedAt = Instant.parse("2026-04-16T10:00:00Z"),
                format = "json",
                chunkSize = 10_000,
                tableSlices = listOf(
                    CheckpointTableSlice(
                        table = "users",
                        status = CheckpointSliceStatus.IN_PROGRESS,
                        resumePosition = CheckpointResumePosition(
                            markerColumn = "seq",
                            markerValue = null,
                            tieBreakerColumns = emptyList(),
                            tieBreakerValues = emptyList(),
                        ),
                    ),
                ),
            )
            store.save(original)
            val loaded = store.load("op-nullmarker")
            loaded.shouldNotBeNull()
            val pos = loaded.tableSlices.single().resumePosition
            pos.shouldNotBeNull()
            pos.markerColumn shouldBe "seq"
            pos.markerValue shouldBe null
            pos.tieBreakerColumns shouldContainExactly emptyList<String>()
            pos.tieBreakerValues shouldContainExactly emptyList<String?>()
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
