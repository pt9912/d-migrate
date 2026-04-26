package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteArtifactOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.IOException
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

    test("missing sidecar over existing .bin throws — §6.3 forbids re-hash recovery") {
        val root = Files.createTempDirectory("d-migrate-no-rehash-")
        val store = FileBackedUploadSegmentStore(root)
        val payload = "abc".toByteArray()
        store.writeSegment(
            UploadSegment("u1", 0, 0, payload.size.toLong(), ""),
            ByteArrayInputStream(payload),
        )
        Files.delete(root.resolve("segments").resolve("u1").resolve("0.meta.json"))
        shouldThrow<IOException> { store.listSegments("u1") }
    }

    test("cleanupOrphans removes data files left behind without sidecar") {
        val root = Files.createTempDirectory("d-migrate-orphan-bin-")
        val store = FileBackedUploadSegmentStore(root)
        val sessionDir = root.resolve("segments").resolve("alive")
        Files.createDirectories(sessionDir)
        Files.write(sessionDir.resolve("3.bin"), byteArrayOf(1, 2, 3))
        val removed = store.cleanupOrphans(activeSessions = setOf("alive"))
        removed shouldBe 1
        Files.exists(sessionDir.resolve("3.bin")) shouldBe false
    }

    test("artifact re-write after sidecar removal throws — §6.3 forbids rehash") {
        val root = Files.createTempDirectory("d-migrate-art-stale-")
        val store = FileBackedArtifactContentStore(root)
        val payload = "hello".toByteArray()
        store.write("art", ByteArrayInputStream(payload), payload.size.toLong())
            .shouldBeInstanceOf<WriteArtifactOutcome.Stored>()
        Files.list(root.resolve("artifacts")).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { shard ->
                val meta = shard.resolve("art.meta.json")
                if (Files.exists(meta)) Files.delete(meta)
            }
        }
        // Re-write attempt: data createLink fails (existing data still
        // present), and the catch path tries to read the sidecar →
        // IOException because the sidecar was manually deleted. The
        // store refuses rather than silently rebuilding from a rehash.
        shouldThrow<IOException> {
            store.write("art", ByteArrayInputStream(payload), payload.size.toLong())
        }
    }
})
