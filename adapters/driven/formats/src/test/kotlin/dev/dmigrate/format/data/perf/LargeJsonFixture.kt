package dev.dmigrate.format.data.perf

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.absolutePathString

/**
 * Deterministischer Generator für große JSON-Top-Level-Array-Fixtures,
 * die der Phase-A-Spike (Schritt 6) und die Phase-B-Reader-Tests gegen
 * einen konstanten Speicherpfad laufen lassen.
 *
 * Plan: implementation-plan-0.4.0.md §4 Phase A Schritt 6, L5, R7.
 *
 * **L5 — nicht eingecheckt, nicht in Standard-CI**: Der Generator
 * erzeugt die Datei einmalig in `build/perf-fixtures/` (Gradle-Build-
 * Directory, gitignored) und cached sie für Folgeläufe. Die Fixture
 * ist NICHT Teil des Repos. Der zugehörige Test ist mit
 * `@Tag("perf")` markiert und läuft nicht in der Default-CI, sondern
 * nur in expliziten lokalen Perf-Runs (siehe §4 Phase A Schritt 6:
 * opt-in via `./gradlew :adapters:driven:formats:test -Dkotest.tags=perf`).
 *
 * **R7 — Cache-Invalidation via Stamp**: Neben dem Fixture schreibt der
 * Generator eine `<fixture>.stamp`-Datei mit dem SHA-256 über Row-Count,
 * Seed, Format-Schema-Version und Generator-Body. Beim Wiederverwenden
 * eines vorhandenen Caches vergleicht [ensureFixture] den aktuellen
 * Stamp gegen den auf Disk und regeneriert deterministisch, wenn die
 * Parameter sich geändert haben. Damit kann ein späteres Refactoring
 * (z.B. Zusatz-Spalte im Schema, neuer Random-Seed) niemals still
 * stale Test-Daten auf einem lokalen `build/perf-fixtures/` produzieren.
 *
 * **Phase A Go/No-Go-Readiness**: Dieser Generator dient sowohl den
 * leichten Scaffold-Tests als auch dem echten Phase-A-Perf-Spike. Der
 * Spike läuft bewusst direkt gegen DSL-JSONs Pull-/Iterator-API statt
 * gegen einen noch nicht existierenden `JsonChunkReader`, damit die
 * Bibliotheksentscheidung schon vor Phase B mit einem realen 100-MB-
 * Streaming-Input geprüft werden kann.
 */
object LargeJsonFixture {

    /**
     * Row-Schema des generierten Arrays. Jedes Objekt sieht aus wie:
     * ```
     * {"id":12345,"email":"user-12345@example.com","score":3.14,"active":true,"tag":"alpha"}
     * ```
     * Das deckt die für §3.5.2 relevanten Typen ab (Integer, String,
     * Double, Boolean), bleibt aber kompakt genug für schnelle Iteration.
     */
    private const val SCHEMA_VERSION = 1

    /**
     * Parameter-Bündel für einen konkreten Fixture-Run. Wird in den
     * Stamp-Hash gemischt, sodass eine Änderung an rows, seed oder der
     * Schema-Version beim nächsten Lauf automatisch eine Neuerzeugung
     * auslöst (R7).
     */
    data class Params(
        val rows: Long,
        val seed: Long,
    ) {
        /**
         * Deterministischer Stamp-Hash (SHA-256 hex) über Row-Count,
         * Seed, Schema-Version und den SHA-256 des aktuellen Source-
         * Inhalts dieses Generators. Damit invalidiert jede relevante
         * Code-Änderung den Cache deterministisch, ohne manuelles
         * Version-Bumping.
         */
        fun stampHex(): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update("rows=$rows|seed=$seed|schema=$SCHEMA_VERSION|".toByteArray())
            md.update(currentGeneratorSourceHash().toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * Stellt sicher, dass in [dir] eine Fixture-Datei mit den
     * spezifizierten [params] existiert. Prüft zuerst die vorhandene
     * `<fixture>.stamp`, regeneriert deterministisch wenn der Stamp
     * abweicht oder wenn die Datei fehlt.
     *
     * @param dir Zielverzeichnis. Wird bei Bedarf angelegt.
     * @param name Dateiname ohne Extension — der Generator schreibt
     *   `<name>.json` und `<name>.json.stamp`.
     * @param params Row-Count + Seed.
     * @return Pfad zur fertig vorbereiteten `.json`-Datei.
     */
    fun ensureFixture(dir: Path, name: String, params: Params): Path {
        Files.createDirectories(dir)
        val fixture = dir.resolve("$name.json")
        val stamp = dir.resolve("$name.json.stamp")

        val expected = params.stampHex()
        if (Files.isRegularFile(fixture) && Files.isRegularFile(stamp)) {
            val onDisk = Files.readString(stamp).trim()
            if (onDisk == expected) {
                // Cache is current; no regeneration needed.
                return fixture
            }
        }

        // Regenerate: write fixture atomically via temp file + rename,
        // then refresh stamp only after the fixture is fully flushed.
        val tempFile = Files.createTempFile(dir, "$name-", ".json.tmp")
        try {
            Files.newOutputStream(tempFile).use { out ->
                writeFixture(out, params)
            }
            Files.move(tempFile, fixture, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (Files.exists(tempFile)) Files.deleteIfExists(tempFile)
        }
        Files.writeString(stamp, expected)
        return fixture
    }

    /**
     * Schreibt ein Top-Level-Array mit exakt [Params.rows] Objekten
     * in den übergebenen [out]. Die Rows sind deterministisch aus dem
     * Seed abgeleitet, damit Reader-Assertions gegen bekannte Werte
     * prüfen können.
     *
     * Implementierungs-Note: wir bauen erst alles in einem
     * [StringBuilder] auf und schreiben am Ende **einmal** als
     * UTF-8-`ByteArray` in den Stream. Das umgeht jegliche
     * BufferedWriter/OutputStreamWriter-Buffering-Subtilität, ist für
     * 100-MB-Fixtures (Phase B) noch zumutbar (~ 200 MB Peak-Heap)
     * und liefert byte-genauen Determinismus über Filesystem-Aufrufe
     * hinweg.
     */
    fun writeFixture(out: OutputStream, params: Params) {
        // We write raw bytes without going through DSL-JSON on purpose:
        // the Phase-B reader MUST consume this file, and we want its
        // *input* to be plain UTF-8 JSON produced by a different
        // serializer than the one under test. A tiny hand-rolled writer
        // keeps the spike honest.
        val sb = StringBuilder(estimateSize(params.rows))
        sb.append('[')
        var rng = params.seed xor 0x5DEECE66DL
        for (i in 0 until params.rows) {
            if (i > 0) sb.append(',')
            sb.append('\n').append("  ")
            rng = nextSeed(rng)
            appendRow(sb, i, rng)
        }
        if (params.rows > 0) sb.append('\n')
        sb.append(']').append('\n')
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun appendRow(sb: StringBuilder, index: Long, rng: Long) {
        val active = (rng and 1L) == 0L
        // Use the mid bits of rng as a deterministic "score" in [0..10).
        val score = ((rng ushr 1) and 0xFFFFL).toDouble() / 6553.6
        val tag = TAGS[((rng ushr 17) and 0xFFL).toInt() % TAGS.size]
        sb.append("""{"id":""").append(index)
        sb.append(""","email":"user-""").append(index).append("""@example.com"""")
        sb.append(""","score":""")
        sb.append("%.4f".format(java.util.Locale.ROOT, score))
        sb.append(""","active":""").append(active)
        sb.append(""","tag":"""").append(tag).append("\"}")
    }

    /**
     * Rough byte-budget pre-estimate for the StringBuilder, so we don't
     * pay for repeated re-allocations on a 100-M-row fixture. Each row
     * fits comfortably under 96 ASCII chars including separators; we
     * pad slightly for headroom.
     */
    private fun estimateSize(rows: Long): Int {
        val perRow = 96 // ASCII chars per row, conservative
        val total = (rows * perRow + 16).coerceAtMost(Int.MAX_VALUE.toLong())
        return total.toInt()
    }

    private val TAGS = listOf("alpha", "bravo", "charlie", "delta", "echo")

    private val sourcePathCandidates = listOf(
        Path.of(
            "adapters",
            "driven",
            "formats",
            "src",
            "test",
            "kotlin",
            "dev",
            "dmigrate",
            "format",
            "data",
            "perf",
            "LargeJsonFixture.kt",
        ),
        Path.of("src", "test", "kotlin", "dev", "dmigrate", "format", "data", "perf", "LargeJsonFixture.kt"),
    )

    internal fun currentGeneratorSourcePath(): Path =
        sourcePathCandidates
            .map { it.toAbsolutePath() }
            .firstOrNull { Files.isRegularFile(it) }
            ?: throw IllegalStateException(
                "Could not locate LargeJsonFixture source file for R7 stamp invalidation. " +
                    "Tried: ${sourcePathCandidates.joinToString()}"
            )

    internal fun currentGeneratorSourceHash(): String {
        val bytes = Files.readAllBytes(currentGeneratorSourcePath())
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun nextSeed(s: Long): Long {
        // Tiny LCG — deterministic, no allocation.
        return (s * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
    }

    /**
     * Profilings-Helfer für den Phase-B-Spike: liefert den aktuellen
     * `used = total - free` Heap-Wert in Bytes. Nicht präzise, aber
     * stabil genug für eine "retained heap nach 100k Rows bleibt in
     * derselben Größenordnung wie einige wenige Chunks, nicht proportional
     * zur Gesamtdatei"-Assertion (§6.2).
     */
    @Suppress("ExplicitGarbageCollectionCall")
    fun usedHeapBytes(): Long {
        System.gc()
        Thread.sleep(50)
        System.gc()
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /**
     * Default-Verzeichnis im Gradle-`build/` des Formats-Moduls. Der
     * relative Pfad stimmt, wenn der Test mit Gradle als Working
     * Directory das Modul-Root benutzt (`:adapters:driven:formats:test`
     * default).
     */
    fun defaultCacheDir(): Path = Path.of("build", "perf-fixtures").toAbsolutePath()

    /** Human-readable path for logging. */
    fun describe(path: Path): String = path.absolutePathString()
}
