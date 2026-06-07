package dev.dmigrate.streaming.checkpoint

import dev.dmigrate.streaming.BundleResumeFingerprint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.time.Instant

/**
 * S8a (AP9 §7.1): Persistenz-Vertrag fuer
 * [CheckpointOperationSpecifics]-Subtypen im [FileCheckpointStore].
 *
 * Round-Trip-Tests fuer `BundleCheckpointSpecifics` und
 * `SingleFileCheckpointSpecifics`, Pflichtfeld-Validierung beim Lesen,
 * sowie `CHECKPOINT_OPERATION_SPECIFICS_UNKNOWN_KIND`-Fail-fast.
 *
 * Bewusst eine eigene Test-Klasse (nicht in [FileCheckpointStoreTest]
 * angehaengt), weil Detekt LargeClass die Hauptklasse sonst sprengt
 * (Memory-Konvention: keine `@Suppress`-Stopgaps fuer Groesse).
 */
class FileCheckpointStoreOperationSpecificsTest : FunSpec({

    fun importManifest(operationId: String) = CheckpointManifest(
        operationId = operationId,
        operationType = CheckpointOperationType.IMPORT,
        createdAt = Instant.parse("2026-04-16T10:00:00Z"),
        updatedAt = Instant.parse("2026-04-16T10:00:00Z"),
        format = "parquet",
        chunkSize = 10_000,
        tableSlices = emptyList(),
    )

    test("save+load roundtrips BundleCheckpointSpecifics") {
        val dir = Files.createTempDirectory("dmigrate-cp-bundle-")
        try {
            val store = FileCheckpointStore(dir)
            val original = importManifest("op-bundle").copy(
                operationSpecific = BundleCheckpointSpecifics(
                    fingerprint = BundleResumeFingerprint(
                        manifestSha256 = "a".repeat(64),
                        formatVersion = "1",
                        producerVersion = "0.9.8",
                        tableOrder = listOf("public.users", "public.orders"),
                    ),
                ),
            )
            store.save(original)
            val loaded = store.load("op-bundle")
            loaded.shouldNotBeNull()
            loaded shouldBe original
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("save+load roundtrips SingleFileCheckpointSpecifics") {
        val dir = Files.createTempDirectory("dmigrate-cp-single-")
        try {
            val store = FileCheckpointStore(dir)
            val original = importManifest("op-single").copy(
                operationSpecific = SingleFileCheckpointSpecifics(
                    contentSha256 = "b".repeat(64),
                    table = "public.events",
                ),
            )
            store.save(original)
            val loaded = store.load("op-single")
            loaded.shouldNotBeNull()
            loaded shouldBe original
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects operationSpecific with unknown kind") {
        val dir = Files.createTempDirectory("dmigrate-cp-unknown-kind-")
        try {
            Files.writeString(
                dir.resolve("op-unknown${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-unknown
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: some-future-format
                  payload: irrelevant
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-unknown") }
            ex.message!! shouldContain "operationSpecific.kind 'some-future-format'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-bundle operationSpecific without fingerprint") {
        val dir = Files.createTempDirectory("dmigrate-cp-bundle-nofp-")
        try {
            Files.writeString(
                dir.resolve("op-bundle-nofp${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-bundle-nofp
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-bundle
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-bundle-nofp") }
            ex.message!! shouldContain "without 'fingerprint'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-bundle fingerprint missing manifestSha256") {
        val dir = Files.createTempDirectory("dmigrate-cp-bundle-nosha-")
        try {
            Files.writeString(
                dir.resolve("op-bundle-nosha${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-bundle-nosha
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-bundle
                  fingerprint:
                    formatVersion: '1'
                    producerVersion: '0.9.8'
                    tableOrder: [public.users]
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-bundle-nosha") }
            ex.message!! shouldContain "without 'manifestSha256'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-bundle fingerprint missing formatVersion") {
        val dir = Files.createTempDirectory("dmigrate-cp-bundle-nofv-")
        try {
            Files.writeString(
                dir.resolve("op-bundle-nofv${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-bundle-nofv
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-bundle
                  fingerprint:
                    manifestSha256: ${"a".repeat(64)}
                    producerVersion: '0.9.8'
                    tableOrder: [public.users]
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-bundle-nofv") }
            ex.message!! shouldContain "without 'formatVersion'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-bundle fingerprint missing producerVersion") {
        val dir = Files.createTempDirectory("dmigrate-cp-bundle-nopv-")
        try {
            Files.writeString(
                dir.resolve("op-bundle-nopv${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-bundle-nopv
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-bundle
                  fingerprint:
                    manifestSha256: ${"a".repeat(64)}
                    formatVersion: '1'
                    tableOrder: [public.users]
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-bundle-nopv") }
            ex.message!! shouldContain "without 'producerVersion'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-bundle fingerprint missing tableOrder") {
        val dir = Files.createTempDirectory("dmigrate-cp-bundle-noto-")
        try {
            Files.writeString(
                dir.resolve("op-bundle-noto${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-bundle-noto
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-bundle
                  fingerprint:
                    manifestSha256: ${"a".repeat(64)}
                    formatVersion: '1'
                    producerVersion: '0.9.8'
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-bundle-noto") }
            ex.message!! shouldContain "without 'tableOrder'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-single-file operationSpecific without contentSha256") {
        val dir = Files.createTempDirectory("dmigrate-cp-single-nosha-")
        try {
            Files.writeString(
                dir.resolve("op-single-nosha${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-single-nosha
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-single-file
                  table: public.events
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-single-nosha") }
            ex.message!! shouldContain "without 'contentSha256'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-single-file operationSpecific without table") {
        val dir = Files.createTempDirectory("dmigrate-cp-single-notbl-")
        try {
            Files.writeString(
                dir.resolve("op-single-notbl${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-single-notbl
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-single-file
                  contentSha256: ${"b".repeat(64)}
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-single-notbl") }
            ex.message!! shouldContain "without 'table'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects parquet-single-file operationSpecific with short contentSha256") {
        val dir = Files.createTempDirectory("dmigrate-cp-single-shortsha-")
        try {
            Files.writeString(
                dir.resolve("op-single-shortsha${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-single-shortsha
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  kind: parquet-single-file
                  contentSha256: deadbeef
                  table: public.events
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-single-shortsha") }
            ex.message!! shouldContain "invalid parquet-single-file operationSpecific"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects non-mapping operationSpecific") {
        val dir = Files.createTempDirectory("dmigrate-cp-opspec-scalar-")
        try {
            Files.writeString(
                dir.resolve("op-opspec-scalar${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-opspec-scalar
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific: not-a-mapping
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-opspec-scalar") }
            ex.message!! shouldContain "non-mapping 'operationSpecific'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load rejects operationSpecific without kind") {
        val dir = Files.createTempDirectory("dmigrate-cp-opspec-nokind-")
        try {
            Files.writeString(
                dir.resolve("op-opspec-nokind${FileCheckpointStore.MANIFEST_SUFFIX}"),
                """
                schemaVersion: 2
                operationId: op-opspec-nokind
                operationType: IMPORT
                createdAt: '2026-04-16T10:00:00Z'
                updatedAt: '2026-04-16T10:00:00Z'
                format: parquet
                chunkSize: 10000
                tableSlices: []
                operationSpecific:
                  payload: irrelevant
                """.trimIndent(),
            )
            val store = FileCheckpointStore(dir)
            val ex = shouldThrow<CheckpointStoreException> { store.load("op-opspec-nokind") }
            ex.message!! shouldContain "without 'kind'"
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("load tolerates manifest without operationSpecific (Pre-AP8 / non-Parquet compat)") {
        val dir = Files.createTempDirectory("dmigrate-cp-noopspec-")
        try {
            val store = FileCheckpointStore(dir)
            val original = importManifest("op-noopspec")
            store.save(original)
            val loaded = store.load("op-noopspec")
            loaded.shouldNotBeNull()
            loaded.operationSpecific shouldBe null
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
})
