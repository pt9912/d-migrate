package dev.dmigrate.server.adapter.storage.file

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.deleteRecursively

/**
 * §6.22 + §6.21 startup-sweep tests for
 * [FileSpoolAssembledUploadPayload.cleanupOrphans]. Bounds disk
 * growth from crashed streaming-finalisation runs that left a spool
 * file behind under `<stateDir>/assembly/<sessionId>/<uuid>.bin`.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class FileSpoolAssemblyOrphanCleanupTest : FunSpec({

    fun touch(path: Path, mtime: Instant) {
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(path, FileTime.from(mtime))
    }

    test("retention=null deletes every spool file under assembly/...") {
        val root = Files.createTempDirectory("dmigrate-spool-sweep-imm-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            touch(root.resolve("assembly/session-1/aaa.bin"), now)
            touch(root.resolve("assembly/session-2/bbb.bin"), now.minusSeconds(7 * 24 * 3600))

            val removed = FileSpoolAssembledUploadPayload.cleanupOrphans(root, olderThan = null)

            removed shouldBe 2
            Files.exists(root.resolve("assembly/session-1/aaa.bin")).shouldBeFalse()
            Files.exists(root.resolve("assembly/session-2/bbb.bin")).shouldBeFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    test("retention=Duration keeps spools newer than the cutoff and drops older ones") {
        val root = Files.createTempDirectory("dmigrate-spool-sweep-dur-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            touch(root.resolve("assembly/session-fresh/keep.bin"), now.minusSeconds(60))
            touch(root.resolve("assembly/session-aged/drop.bin"), now.minus(Duration.ofDays(2)))

            val removed = FileSpoolAssembledUploadPayload.cleanupOrphans(
                root = root,
                olderThan = Duration.ofHours(24),
                clock = clock,
            )

            removed shouldBe 1
            Files.exists(root.resolve("assembly/session-fresh/keep.bin")).shouldBeTrue()
            Files.exists(root.resolve("assembly/session-aged/drop.bin")).shouldBeFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    test("tmp leftovers are removed but do not contribute to the .bin count") {
        val root = Files.createTempDirectory("dmigrate-spool-sweep-tmp-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            touch(root.resolve("assembly/session-x/active.bin"), now.minusSeconds(7 * 24 * 3600))
            touch(root.resolve("assembly/session-x/active.bin.tmp.abcd"), now.minusSeconds(7 * 24 * 3600))

            val removed = FileSpoolAssembledUploadPayload.cleanupOrphans(root, olderThan = null)

            // Only the .bin counts; the .tmp.* leftover is silently dropped.
            removed shouldBe 1
            Files.exists(root.resolve("assembly/session-x/active.bin")).shouldBeFalse()
            Files.exists(root.resolve("assembly/session-x/active.bin.tmp.abcd")).shouldBeFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    test("unknown files outside the spool layout are left untouched") {
        val root = Files.createTempDirectory("dmigrate-spool-sweep-foreign-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            touch(root.resolve("assembly/README.txt"), now.minus(Duration.ofDays(30)))
            touch(root.resolve("assembly/session-y/notes.md"), now.minus(Duration.ofDays(30)))

            FileSpoolAssembledUploadPayload.cleanupOrphans(root, olderThan = null) shouldBe 0
            Files.exists(root.resolve("assembly/README.txt")).shouldBeTrue()
            Files.exists(root.resolve("assembly/session-y/notes.md")).shouldBeTrue()
        } finally {
            root.deleteRecursively()
        }
    }

    test("missing assembly root returns 0") {
        val root = Files.createTempDirectory("dmigrate-spool-sweep-empty-")
        try {
            FileSpoolAssembledUploadPayload.cleanupOrphans(root, olderThan = null) shouldBe 0
        } finally {
            root.deleteRecursively()
        }
    }
})
