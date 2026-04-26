package dev.dmigrate.server.adapter.storage.file

import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.ports.WriteArtifactOutcome
import dev.dmigrate.server.ports.WriteSegmentOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileBackedConcurrentWriteTest : FunSpec({

    test("identical concurrent writes resolve to one Stored and the rest AlreadyStored") {
        val store = FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-concurrent-same-"))
        val payload = "abcdefghij".toByteArray()
        val workers = 6
        val outcomes = raceWorkers(workers) {
            val seg = UploadSegment("ses", 0, 0, payload.size.toLong(), "")
            store.writeSegment(seg, ByteArrayInputStream(payload))
        }
        outcomes.count { it is WriteSegmentOutcome.Stored } shouldBe 1
        outcomes.count { it is WriteSegmentOutcome.AlreadyStored } shouldBe workers - 1
    }

    test("concurrent writes with conflicting bytes resolve to one Stored and the rest Conflict") {
        val store = FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-concurrent-conflict-"))
        val workers = 4
        val outcomes = raceWorkers(workers) { idx ->
            val payload = "payload-$idx-fixedlen".toByteArray()
            val seg = UploadSegment("ses", 0, 0, payload.size.toLong(), "")
            store.writeSegment(seg, ByteArrayInputStream(payload))
        }
        outcomes.count { it is WriteSegmentOutcome.Stored } shouldBe 1
        outcomes.count { it is WriteSegmentOutcome.Conflict } shouldBe workers - 1
        val winner = outcomes.filterIsInstance<WriteSegmentOutcome.Stored>().single().segment.segmentSha256
        outcomes.filterIsInstance<WriteSegmentOutcome.Conflict>().forEach {
            it.existingSegmentSha256 shouldBe winner
        }
    }

    test("artifact concurrent writes with same bytes converge on AlreadyExists") {
        val store = FileBackedArtifactContentStore(Files.createTempDirectory("d-migrate-art-concurrent-"))
        val payload = "static-content".toByteArray()
        val workers = 6
        val outcomes = raceWorkers(workers) {
            store.write("dup", ByteArrayInputStream(payload), payload.size.toLong())
        }
        outcomes.count { it is WriteArtifactOutcome.Stored } shouldBe 1
        outcomes.count { it is WriteArtifactOutcome.AlreadyExists } shouldBe workers - 1
        store.exists("dup") shouldBe true
    }

    test("conflict outcome surfaces existing and attempted hashes") {
        val store = FileBackedUploadSegmentStore(Files.createTempDirectory("d-migrate-conflict-detail-"))
        val first = "alpha".toByteArray()
        val second = "beta_".toByteArray()
        store.writeSegment(
            UploadSegment("u1", 0, 0, first.size.toLong(), ""),
            ByteArrayInputStream(first),
        ).shouldBeInstanceOf<WriteSegmentOutcome.Stored>()
        val outcome = store.writeSegment(
            UploadSegment("u1", 0, 0, second.size.toLong(), ""),
            ByteArrayInputStream(second),
        )
        outcome.shouldBeInstanceOf<WriteSegmentOutcome.Conflict>()
        outcome.existingSegmentSha256.length shouldBe 64
        outcome.attemptedSegmentSha256.length shouldBe 64
        (outcome.existingSegmentSha256 == outcome.attemptedSegmentSha256) shouldBe false
    }
})

private fun <T> raceWorkers(n: Int, task: (Int) -> T): List<T> {
    val ready = CountDownLatch(n)
    val go = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(n)
    return try {
        (0 until n).map { idx ->
            pool.submit<T> {
                ready.countDown()
                go.await(2, TimeUnit.SECONDS)
                task(idx)
            }
        }.also {
            ready.await(2, TimeUnit.SECONDS)
            go.countDown()
        }.map { it.get(5, TimeUnit.SECONDS) }
    } finally {
        pool.shutdown()
    }
}
