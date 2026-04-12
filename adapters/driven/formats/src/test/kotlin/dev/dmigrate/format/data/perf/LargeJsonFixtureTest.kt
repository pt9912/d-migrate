package dev.dmigrate.format.data.perf

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.absolutePathString

/**
 * Phase A Schritt 6 scaffold: stamps, deterministic generation, and
 * cache invalidation for the [LargeJsonFixture].
 *
 * **Not the actual Go/No-Go spike.** The real 100-MB pull-parse test
 * lives in the sibling `LargeJsonPullSpikePerfTest` and is tagged
 * `perf`. This suite only verifies the scaffold:
 *
 * 1. The generator is deterministic (same params → byte-identical output)
 * 2. The stamp file is written next to the fixture
 * 3. A second `ensureFixture` call with identical params short-circuits
 * 4. Changing params (rows or seed) invalidates the cache and
 *    regenerates the fixture atomically
 *
 * All test cases here use tiny row counts (10–50 rows) and complete in
 * well under a second, so they stay in the default CI lane. The real
 * **100-MB Go/No-Go run** is opt-in via `-Dkotest.tags=perf`.
 *
 * Local perf run:
 *
 * ```
 * docker build --target build \
 *   --build-arg GRADLE_TASKS=":adapters:driven:formats:test -Dkotest.tags=perf" \
 *   -t d-migrate:phase-b-perf .
 * ```
 */
class LargeJsonFixtureTest : FunSpec({

    test("ensureFixture creates the file and a sibling .stamp") {
        val dir = Files.createTempDirectory("d-migrate-largejson-")
        try {
            val params = LargeJsonFixture.Params(rows = 10, seed = 42L)
            val path = LargeJsonFixture.ensureFixture(dir, "tiny", params)
            Files.isRegularFile(path) shouldBe true
            Files.isRegularFile(dir.resolve("tiny.json.stamp")) shouldBe true
            Files.size(path) shouldBeGreaterThan 10L
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ensureFixture is byte-deterministic for identical params") {
        val dir = Files.createTempDirectory("d-migrate-largejson-")
        try {
            val params = LargeJsonFixture.Params(rows = 50, seed = 7L)
            val a = LargeJsonFixture.ensureFixture(dir.resolve("a"), "f", params)
            val b = LargeJsonFixture.ensureFixture(dir.resolve("b"), "f", params)
            Files.readAllBytes(a).contentEquals(Files.readAllBytes(b)) shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("ensureFixture short-circuits when stamp matches (no rewrite)") {
        val dir = Files.createTempDirectory("d-migrate-largejson-")
        try {
            val params = LargeJsonFixture.Params(rows = 20, seed = 1L)
            val first = LargeJsonFixture.ensureFixture(dir, "same", params)
            val mtime1 = Files.getLastModifiedTime(first)

            // Sleep just enough that a real rewrite would bump the mtime.
            Thread.sleep(50)

            val second = LargeJsonFixture.ensureFixture(dir, "same", params)
            val mtime2 = Files.getLastModifiedTime(second)

            first shouldBe second
            // Same stamp → no rewrite → mtime unchanged.
            (mtime1 == mtime2) shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("R7: changing params invalidates cache and regenerates") {
        val dir = Files.createTempDirectory("d-migrate-largejson-")
        try {
            val first = LargeJsonFixture.ensureFixture(
                dir,
                "versioned",
                LargeJsonFixture.Params(rows = 10, seed = 1L),
            )
            val bytesA = Files.readAllBytes(first)
            val stampA = Files.readString(dir.resolve("versioned.json.stamp")).trim()

            // Different seed → different stamp → regeneration. Note that
            // `first` and `second` are the SAME Path (`dir/versioned.json`)
            // because we reuse the name — the second call overwrites.
            val second = LargeJsonFixture.ensureFixture(
                dir,
                "versioned",
                LargeJsonFixture.Params(rows = 10, seed = 99L),
            )
            val bytesB = Files.readAllBytes(second)
            val stampB = Files.readString(dir.resolve("versioned.json.stamp")).trim()

            (bytesA.contentEquals(bytesB)) shouldBe false
            (stampA == stampB) shouldBe false

            // Capture size BEFORE the third call — `Files.size(second)`
            // after the third call would re-read the now-overwritten file
            // and the assertion would always be a no-op.
            val sizeBeforeThird = Files.size(second)

            // And again, different row count.
            val third = LargeJsonFixture.ensureFixture(
                dir,
                "versioned",
                LargeJsonFixture.Params(rows = 20, seed = 99L),
            )
            Files.size(third) shouldBeGreaterThan sizeBeforeThird
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("generated fixture begins with '[' and ends with ']\\n'") {
        val dir = Files.createTempDirectory("d-migrate-largejson-")
        try {
            val path = LargeJsonFixture.ensureFixture(
                dir,
                "shape",
                LargeJsonFixture.Params(rows = 5, seed = 3L),
            )
            val text = Files.readString(path)
            text shouldStartWith "["
            text.trimEnd().endsWith("]") shouldBe true
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    test("stamp hex is 64 chars (SHA-256)") {
        val stamp = LargeJsonFixture.Params(rows = 1, seed = 0L).stampHex()
        stamp.length shouldBe 64
    }

    test("R7: generator source hash is derived from the actual source file content") {
        val sourceBytes = Files.readAllBytes(LargeJsonFixture.currentGeneratorSourcePath())
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(sourceBytes)
            .joinToString("") { "%02x".format(it) }
        LargeJsonFixture.currentGeneratorSourceHash() shouldBe expected
    }

    test("usedHeapBytes returns a positive number (smoke)") {
        LargeJsonFixture.usedHeapBytes() shouldBeGreaterThan 0L
    }

    test("describe path returns the absolute form") {
        val tmp = Files.createTempFile("describe-", ".json")
        try {
            LargeJsonFixture.describe(tmp) shouldBe tmp.absolutePathString()
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    test("defaultCacheDir resolves under build/perf-fixtures") {
        val d = LargeJsonFixture.defaultCacheDir()
        // The path is build-dir relative, but absolutized. Just check
        // the segment is in the path.
        d.toString().contains("perf-fixtures") shouldBe true
    }
})
