package dev.dmigrate.format.data.yaml

import dev.dmigrate.format.data.perf.LargeJsonFixture
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.floor

private val PerfTag = NamedTag("perf")

/**
 * Phase B Schritt 8: Spec-/Heap-Test für den YAML-Reader.
 *
 * Liest eine deterministische 100k-Row-YAML-Datei aus `build/perf-fixtures`
 * und prüft, dass der Reader den Input ohne Speicherwachstum proportional
 * zur Dateigröße verarbeiten kann.
 */
class YamlChunkReaderPerfTest : FunSpec({

    tags(PerfTag)

    test("perf 100k fixture: YamlChunkReader streams within heap budget") {
        val params = LargeYamlFixture.Params(rows = 100_000L)
        val fixture = LargeYamlFixture.ensureFixture(
            dir = LargeJsonFixture.defaultCacheDir(),
            name = "phase-b-yaml-reader-100k-v2",
            params = params,
        )

        val chunkSize = 10_000
        val heapBefore = LargeJsonFixture.usedHeapBytes()

        var rowsSeen = 0L
        var firstId: Any? = null
        var firstScore: Any? = null
        var lastId: Any? = null
        var maxRetainedHeap = heapBefore

        Files.newInputStream(fixture).use { input ->
            YamlChunkReader(input, "perf", chunkSize).use { reader ->
                reader.headerColumns() shouldBe listOf("id", "email", "score", "active", "tag")

                var chunk = reader.nextChunk()
                while (chunk != null) {
                    for (row in chunk.rows) {
                        if (rowsSeen == 0L) {
                            firstId = row[0]
                            firstScore = row[2]
                        }
                        lastId = row[0]
                        rowsSeen++
                    }

                    if (rowsSeen % 10_000L == 0L) {
                        val retained = LargeJsonFixture.usedHeapBytes()
                        if (retained > maxRetainedHeap) {
                            maxRetainedHeap = retained
                        }
                    }

                    chunk = reader.nextChunk()
                }
            }
        }

        val heapAfter = LargeJsonFixture.usedHeapBytes()
        maxRetainedHeap = maxOf(maxRetainedHeap, heapAfter)

        rowsSeen shouldBe params.rows
        firstId.shouldBeInstanceOf<Long>()
        firstId shouldBe 0L
        lastId shouldBe params.rows - 1

        firstScore.shouldBeInstanceOf<Double>()
        val score = firstScore as Double
        score.isFinite().shouldBeTrue()
        (score != floor(score)).shouldBeTrue()

        val retainedGrowth = maxRetainedHeap - heapBefore
        retainedGrowth shouldBeLessThan (32L * 1024L * 1024L)
    }
})

private object LargeYamlFixture {
    private val tags = listOf("alpha", "bravo", "charlie", "delta", "echo")

    data class Params(
        val rows: Long,
    )

    fun ensureFixture(dir: Path, name: String, params: Params): Path {
        Files.createDirectories(dir)
        val fixture = dir.resolve("$name.yaml")
        if (Files.isRegularFile(fixture)) return fixture

        Files.newBufferedWriter(fixture, Charsets.UTF_8).use { writer ->
            for (i in 0 until params.rows) {
                val score = ((i % 10_000L).toDouble() / 1000.0) + 0.125
                writer.append("- id: ").append(i.toString()).append('\n')
                writer.append("  email: 'user-").append(i.toString()).append("@example.com'").append('\n')
                writer.append("  score: ")
                    .append("%.4f".format(Locale.ROOT, score))
                    .append('\n')
                writer.append("  active: ").append(if (i % 2L == 0L) "true" else "false").append('\n')
                writer.append("  tag: '").append(tags[(i % tags.size.toLong()).toInt()]).append('\'').append('\n')
            }
        }
        return fixture
    }
}
