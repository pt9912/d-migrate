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
 * §6.21 startup-sweep tests for [FileBackedArtifactContentStore.cleanupOrphans].
 *
 * The companion-level helper bounds disk growth across restarts when
 * the artefact metadata store is in-memory: every file at boot is
 * unreferenceable, so retention is the only guard.
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class FileBackedArtifactContentStoreCleanupTest : FunSpec({

    fun touch(path: Path, mtime: Instant) {
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf(1, 2, 3))
        Files.setLastModifiedTime(path, FileTime.from(mtime))
    }

    test("retention=null deletes every store file unconditionally") {
        val root = Files.createTempDirectory("dmigrate-artefact-sweep-imm-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            touch(root.resolve("artifacts/ab/x.bin"), now)
            touch(root.resolve("artifacts/ab/x.meta.json"), now)
            touch(root.resolve("artifacts/cd/y.bin"), now.minus(Duration.ofDays(7)))
            touch(root.resolve("artifacts/cd/y.meta.json"), now.minus(Duration.ofDays(7)))

            val removed = FileBackedArtifactContentStore.cleanupOrphans(root, olderThan = null)

            removed shouldBe 2
            Files.exists(root.resolve("artifacts/ab/x.bin")).shouldBeFalse()
            Files.exists(root.resolve("artifacts/ab/x.meta.json")).shouldBeFalse()
            Files.exists(root.resolve("artifacts/cd/y.bin")).shouldBeFalse()
            Files.exists(root.resolve("artifacts/cd/y.meta.json")).shouldBeFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    test("retention=Duration keeps files newer than the cutoff and deletes older ones") {
        val root = Files.createTempDirectory("dmigrate-artefact-sweep-dur-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)

            // Newer than 24h cutoff: keep.
            touch(root.resolve("artifacts/ab/fresh.bin"), now.minus(Duration.ofHours(1)))
            touch(root.resolve("artifacts/ab/fresh.meta.json"), now.minus(Duration.ofHours(1)))
            // Older than 24h cutoff: drop.
            touch(root.resolve("artifacts/cd/aged.bin"), now.minus(Duration.ofDays(7)))
            touch(root.resolve("artifacts/cd/aged.meta.json"), now.minus(Duration.ofDays(7)))

            val removed = FileBackedArtifactContentStore.cleanupOrphans(
                root = root,
                olderThan = Duration.ofHours(24),
                clock = clock,
            )

            removed shouldBe 1
            Files.exists(root.resolve("artifacts/ab/fresh.bin")).shouldBeTrue()
            Files.exists(root.resolve("artifacts/ab/fresh.meta.json")).shouldBeTrue()
            Files.exists(root.resolve("artifacts/cd/aged.bin")).shouldBeFalse()
            Files.exists(root.resolve("artifacts/cd/aged.meta.json")).shouldBeFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    test("dangling sidecar without a matching .bin is removed when older than cutoff") {
        val root = Files.createTempDirectory("dmigrate-artefact-sweep-dangling-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)

            touch(root.resolve("artifacts/ef/orphan.meta.json"), now.minus(Duration.ofDays(7)))
            touch(root.resolve("artifacts/ef/recent.meta.json"), now.minus(Duration.ofMinutes(5)))

            val removed = FileBackedArtifactContentStore.cleanupOrphans(
                root = root,
                olderThan = Duration.ofHours(24),
                clock = clock,
            )

            // No .bin removals to count; only sidecars cleaned silently.
            removed shouldBe 0
            Files.exists(root.resolve("artifacts/ef/orphan.meta.json")).shouldBeFalse()
            Files.exists(root.resolve("artifacts/ef/recent.meta.json")).shouldBeTrue()
        } finally {
            root.deleteRecursively()
        }
    }

    test("tmp leftovers are removed when older than cutoff") {
        val root = Files.createTempDirectory("dmigrate-artefact-sweep-tmp-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)

            touch(
                root.resolve("artifacts/ab/x.bin.tmp.abcd-1234"),
                now.minus(Duration.ofDays(7)),
            )

            val removed = FileBackedArtifactContentStore.cleanupOrphans(
                root = root,
                olderThan = Duration.ofHours(24),
                clock = clock,
            )

            removed shouldBe 0
            Files.exists(root.resolve("artifacts/ab/x.bin.tmp.abcd-1234")).shouldBeFalse()
        } finally {
            root.deleteRecursively()
        }
    }

    test("unknown files outside the store layout are left untouched") {
        val root = Files.createTempDirectory("dmigrate-artefact-sweep-foreign-")
        try {
            val now = Instant.parse("2026-05-03T10:00:00Z")
            touch(root.resolve("artifacts/README.txt"), now.minus(Duration.ofDays(30)))
            touch(root.resolve("artifacts/ab/notes.md"), now.minus(Duration.ofDays(30)))

            val removed = FileBackedArtifactContentStore.cleanupOrphans(root, olderThan = null)

            removed shouldBe 0
            Files.exists(root.resolve("artifacts/README.txt")).shouldBeTrue()
            Files.exists(root.resolve("artifacts/ab/notes.md")).shouldBeTrue()
        } finally {
            root.deleteRecursively()
        }
    }

    test("missing artifacts root returns 0") {
        val root = Files.createTempDirectory("dmigrate-artefact-sweep-empty-")
        try {
            FileBackedArtifactContentStore.cleanupOrphans(root, olderThan = null) shouldBe 0
        } finally {
            root.deleteRecursively()
        }
    }
})
