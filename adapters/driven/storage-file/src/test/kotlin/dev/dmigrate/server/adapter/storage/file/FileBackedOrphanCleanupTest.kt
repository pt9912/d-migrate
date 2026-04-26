package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteArtifactOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.nio.file.Files

class FileBackedOrphanCleanupTest : FunSpec({

    test("cleanupOrphans removes session directories not in active set") {
        val root = Files.createTempDirectory("d-migrate-orphan-")
        val store = FileBackedUploadSegmentStore(root)
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("alive", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        store.writeSegment(
            UploadSegment("orphan", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        val removed = store.cleanupOrphans(activeSessions = setOf("alive"))
        removed shouldBe 1
        Files.exists(root.resolve("segments").resolve("orphan")) shouldBe false
        Files.exists(root.resolve("segments").resolve("alive").resolve("0.bin")) shouldBe true
    }

    test("cleanupOrphans removes leftover .tmp.* files inside active sessions") {
        val root = Files.createTempDirectory("d-migrate-tmp-leftover-")
        val store = FileBackedUploadSegmentStore(root)
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("alive", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        val orphanTmp = root.resolve("segments").resolve("alive").resolve("99.bin.tmp.aaaa-bbbb")
        Files.write(orphanTmp, byteArrayOf(1, 2, 3))
        val removed = store.cleanupOrphans(activeSessions = setOf("alive"))
        removed shouldBe 1
        Files.exists(orphanTmp) shouldBe false
        Files.exists(root.resolve("segments").resolve("alive").resolve("0.bin")) shouldBe true
    }

    test("cleanupOrphans removes dangling sidecars without matching .bin") {
        val root = Files.createTempDirectory("d-migrate-dangling-")
        val store = FileBackedUploadSegmentStore(root)
        val sessionDir = root.resolve("segments").resolve("alive")
        Files.createDirectories(sessionDir)
        Files.write(
            sessionDir.resolve("7.meta.json"),
            """{"sha256":"${"0".repeat(64)}","sizeBytes":1}""".toByteArray(),
        )
        val removed = store.cleanupOrphans(activeSessions = setOf("alive"))
        removed shouldBe 1
        Files.exists(sessionDir.resolve("7.meta.json")) shouldBe false
    }

    test("recovery rebuilds sidecar from existing .bin if missing") {
        val root = Files.createTempDirectory("d-migrate-recovery-")
        val store = FileBackedUploadSegmentStore(root)
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        Files.delete(root.resolve("segments").resolve("u1").resolve("0.meta.json"))
        store.listSegments("u1").map { it.segmentIndex } shouldBe listOf(0)
        Files.exists(root.resolve("segments").resolve("u1").resolve("0.meta.json")) shouldBe true
    }

    test("artifact recovery rebuilds sidecar after sidecar removal") {
        val root = Files.createTempDirectory("d-migrate-art-recover-")
        val store = FileBackedArtifactContentStore(root)
        val payload = "hello".toByteArray()
        val first = store.write("art", ByteArrayInputStream(payload), payload.size.toLong())
        first.shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
        Files.list(root.resolve("artifacts")).use { stream ->
            stream.filter { it.fileName.toString() != "__staging" && Files.isDirectory(it) }
                .forEach { shard ->
                    val meta = shard.resolve("art.meta.json")
                    if (Files.exists(meta)) Files.delete(meta)
                }
        }
        val second = store.write("art", ByteArrayInputStream(payload), payload.size.toLong())
        second.shouldBeInstanceOf<WriteArtifactOutcome.AlreadyExists>()
        second.existingSha256 shouldBe first.sha256
    }
})
