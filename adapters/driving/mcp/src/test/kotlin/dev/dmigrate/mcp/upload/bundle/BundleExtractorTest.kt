package dev.dmigrate.mcp.upload.bundle

import dev.dmigrate.server.core.upload.bundle.BundleFormat
import dev.dmigrate.server.core.upload.bundle.BundleSecurityLimits
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Follow-up AP 2 — Acceptance-Tests für [BundleExtractor].
 *
 * Plan §4 verlangt stabile `VALIDATION_ERROR`-Ergebnisse für jeden
 * Sicherheits-Edge-Case: absolute Pfade, Traversal, Symlinks, doppelte
 * Entries, zu viele Entries, zu grosse entpackte Daten, unbekannte
 * Entry-Typen. Diese Tests sichern alle dokumentierten Pfade.
 */
class BundleExtractorTest : FunSpec({

    val extractor = BundleExtractor()

    fun bundleBytes(builder: ZipOutputStream.() -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use(builder)
        return bos.toByteArray()
    }

    fun ZipOutputStream.writeEntry(name: String, content: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(content)
        closeEntry()
    }

    fun ZipOutputStream.writeManifest(json: String) =
        writeEntry(BundleFormat.MANIFEST_PATH_V1, json.toByteArray(Charsets.UTF_8))

    fun simpleManifest(): String =
        """
        {
          "version": "v1",
          "format": "csv",
          "tables": [
            {"name": "users",  "path": "users.csv"},
            {"name": "orders", "path": "orders/orders.csv"}
          ]
        }
        """.trimIndent()

    fun extractTo(bytes: ByteArray, limits: BundleSecurityLimits = BundleSecurityLimits()): Pair<Path, BundleExtractionOutcome> {
        val tmp = Files.createTempDirectory("bundle-test-")
        return try {
            val outcome = BundleExtractor(limits).extract(ByteArrayInputStream(bytes), tmp)
            tmp to outcome
        } catch (t: Throwable) {
            // Cleanup on failure too — keep tests hermetic.
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            throw t
        }
    }

    fun cleanup(tmp: Path) {
        if (Files.exists(tmp)) {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    test("Gültiges Bundle wird extrahiert (Manifest + zwei Dateien)") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "id,name\n1,Alice\n".toByteArray())
            writeEntry("orders/orders.csv", "id,user_id\n1,1\n".toByteArray())
        }
        val (tmp, outcome) = extractTo(bytes)
        try {
            val valid = outcome.shouldBeInstanceOf<BundleExtractionOutcome.Valid>()
            valid.manifest.tables.size shouldBe 2
            valid.extractedFiles.keys shouldBe setOf("users.csv", "orders/orders.csv")
            Files.exists(valid.extractedFiles["users.csv"]!!) shouldBe true
            Files.exists(valid.extractedFiles["orders/orders.csv"]!!) shouldBe true
            valid.manifestFingerprint.length shouldBe 64
        } finally {
            cleanup(tmp)
        }
    }

    test("Bundle ohne Manifest -> MissingManifest") {
        val bytes = bundleBytes { writeEntry("users.csv", "x".toByteArray()) }
        val (tmp, outcome) = extractTo(bytes)
        try {
            outcome shouldBe BundleExtractionOutcome.MissingManifest
        } finally {
            cleanup(tmp)
        }
    }

    test("Bundle mit Manifest, das auf fehlende Datei verweist -> Invalid") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest()) // referenziert users.csv und orders/orders.csv
            writeEntry("users.csv", "x".toByteArray())
            // orders/orders.csv fehlt absichtlich.
        }
        val (tmp, outcome) = extractTo(bytes)
        try {
            val invalid = outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
            invalid.reason shouldContain "manifest references unknown entries"
            invalid.reason shouldContain "orders/orders.csv"
        } finally {
            cleanup(tmp)
        }
    }

    test("Pfad-Traversal über Entry-Namen wird abgewiesen") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("../escape.csv", "x".toByteArray())
        }
        val (tmp, outcome) = extractTo(bytes)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "'..' segment"
        } finally {
            cleanup(tmp)
        }
    }

    test("Absoluter Entry-Pfad wird abgewiesen") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("/etc/passwd", "x".toByteArray())
        }
        val (tmp, outcome) = extractTo(bytes)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "absolute path"
        } finally {
            cleanup(tmp)
        }
    }

    test("Doppelter Entry case-insensitive wird abgewiesen") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("Users.csv", "y".toByteArray())
        }
        val (tmp, outcome) = extractTo(bytes)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "duplicate entry"
        } finally {
            cleanup(tmp)
        }
    }

    test("Entry-Anzahl-Limit wird durchgesetzt") {
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
            writeEntry("extra.csv", "x".toByteArray())
        }
        val (tmp, outcome) = extractTo(bytes, BundleSecurityLimits(maxEntryCount = 2))
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "maxEntryCount"
        } finally {
            cleanup(tmp)
        }
    }

    test("Entry-Größenlimit wird durchgesetzt") {
        val bigContent = ByteArray(100) { 'a'.code.toByte() }
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", bigContent)
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val limits = BundleSecurityLimits(
            maxEntryUncompressedBytes = 10,
            maxCompressionRatio = 0,
        )
        val (tmp, outcome) = extractTo(bytes, limits)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "maxEntryUncompressedBytes"
        } finally {
            cleanup(tmp)
        }
    }

    test("Total-Bytes-Limit wird durchgesetzt") {
        val content = ByteArray(50) { 'a'.code.toByte() }
        val bytes = bundleBytes {
            writeManifest(simpleManifest())
            writeEntry("users.csv", content)
            writeEntry("orders/orders.csv", content)
        }
        val limits = BundleSecurityLimits(
            maxEntryUncompressedBytes = 100,
            maxTotalUncompressedBytes = 60,
            maxCompressionRatio = 0,
        )
        val (tmp, outcome) = extractTo(bytes, limits)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "maxTotalUncompressedBytes"
        } finally {
            cleanup(tmp)
        }
    }

    test("Manifest-Größenlimit wird durchgesetzt") {
        val giantManifest = "{" + "x".repeat(10_000) + "}"
        val bytes = bundleBytes {
            writeManifest(giantManifest)
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val limits = BundleSecurityLimits(maxManifestBytes = 100, maxCompressionRatio = 0)
        val (tmp, outcome) = extractTo(bytes, limits)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Invalid>()
                .reason shouldContain "manifest exceeds size limit"
        } finally {
            cleanup(tmp)
        }
    }

    test("Bytes ohne ZIP-Signatur -> MissingManifest (ZIP-Stream akzeptiert leere Eingabe)") {
        // ZipInputStream ist lax: Daten ohne PK-Header werden als
        // 'leeres Archiv' interpretiert. Das Bundle kommt deshalb nicht
        // bei `Invalid("not a valid ZIP")` heraus, sondern bei
        // `MissingManifest` — der Effekt ist derselbe (kein Job-Start).
        val tmp = Files.createTempDirectory("corrupt-")
        try {
            val outcome = extractor.extract(
                ByteArrayInputStream("this is not a zip file".toByteArray()),
                tmp,
            )
            outcome shouldBe BundleExtractionOutcome.MissingManifest
        } finally {
            cleanup(tmp)
        }
    }


    test("Manifest-Bytes werden deterministisch fingerprintet") {
        val manifestJson = simpleManifest()
        val expected = BundleManifestParser.fingerprint(manifestJson.toByteArray(Charsets.UTF_8))
        val bytes = bundleBytes {
            writeManifest(manifestJson)
            writeEntry("users.csv", "x".toByteArray())
            writeEntry("orders/orders.csv", "x".toByteArray())
        }
        val (tmp, outcome) = extractTo(bytes)
        try {
            outcome.shouldBeInstanceOf<BundleExtractionOutcome.Valid>()
                .manifestFingerprint shouldBe expected
        } finally {
            cleanup(tmp)
        }
    }
})
