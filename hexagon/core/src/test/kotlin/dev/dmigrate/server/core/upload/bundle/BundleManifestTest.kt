package dev.dmigrate.server.core.upload.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Follow-up AP 2 — Form-Invarianten der Manifest-Datentypen.
 *
 * Diese Tests sichern nur die Init-Block-Pflichten (Pfad-Hygiene,
 * Duplicate-Check) — die semantischen Adapter-Tests (Parser-Verhalten,
 * Wire-Form, Bundle-Extraktion) leben in `adapters/driving/mcp` bzw.
 * `hexagon/application`.
 */
class BundleManifestTest : FunSpec({

    fun entry(name: String = "users", path: String = "users.csv") =
        BundleManifestEntry(name = name, path = path)

    test("BundleManifest mit gültigen Feldern wird akzeptiert") {
        val manifest = BundleManifest(
            version = BundleFormat.MANIFEST_VERSION_V1,
            format = "csv",
            tables = listOf(entry()),
        )
        manifest.tables.size shouldBe 1
    }

    test("Leere Tabellen-Liste wird abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifest(version = "v1", format = "csv", tables = emptyList())
        }.message!! shouldContain "tables must not be empty"
    }

    test("Doppelte Tabellennamen werden abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifest(
                version = "v1",
                format = "csv",
                tables = listOf(entry(name = "users"), entry(name = "users", path = "u2.csv")),
            )
        }.message!! shouldContain "duplicate table names"
    }

    test("Doppelte Pfade werden abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifest(
                version = "v1",
                format = "csv",
                tables = listOf(entry(name = "a", path = "x.csv"), entry(name = "b", path = "x.csv")),
            )
        }.message!! shouldContain "duplicate paths"
    }

    test("Blanker version/format wird abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifest(version = "", format = "csv", tables = listOf(entry()))
        }
        shouldThrow<IllegalArgumentException> {
            BundleManifest(version = "v1", format = " ", tables = listOf(entry()))
        }
    }

    test("BundleManifestEntry: absoluter Pfad wird abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifestEntry(name = "users", path = "/etc/passwd")
        }.message!! shouldContain "must not be absolute"
    }

    test("BundleManifestEntry: Backslash-Pfad wird abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifestEntry(name = "users", path = "windows\\users.csv")
        }.message!! shouldContain "backslash"
    }

    test("BundleManifestEntry: '..'-Segment wird abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifestEntry(name = "users", path = "../escape.csv")
        }.message!! shouldContain "'..' segments"
    }

    test("BundleManifestEntry: Leerer Name oder Pfad wird abgewiesen") {
        shouldThrow<IllegalArgumentException> {
            BundleManifestEntry(name = "", path = "users.csv")
        }
        shouldThrow<IllegalArgumentException> {
            BundleManifestEntry(name = "users", path = "")
        }
    }

    test("BundleSecurityLimits Defaults sind aktiv") {
        val limits = BundleSecurityLimits()
        limits.maxEntryCount shouldBe BundleSecurityLimits.DEFAULT_MAX_ENTRY_COUNT
        limits.maxCompressionRatio shouldBe BundleSecurityLimits.DEFAULT_MAX_COMPRESSION_RATIO
    }

    test("BundleSecurityLimits: nicht-positive Werte werden abgewiesen") {
        shouldThrow<IllegalArgumentException> { BundleSecurityLimits(maxEntryCount = 0) }
        shouldThrow<IllegalArgumentException> { BundleSecurityLimits(maxTotalUncompressedBytes = 0) }
        shouldThrow<IllegalArgumentException> { BundleSecurityLimits(maxEntryUncompressedBytes = -1) }
        shouldThrow<IllegalArgumentException> { BundleSecurityLimits(maxCompressionRatio = -1) }
    }

    test("BundleFormat.ALL enthält v1.zip und kennt MANIFEST_PATH_V1") {
        BundleFormat.ALL.contains(BundleFormat.SEED_BUNDLE_V1_ZIP) shouldBe true
        BundleFormat.MANIFEST_PATH_V1 shouldBe "manifest.json"
        BundleFormat.MANIFEST_VERSION_V1 shouldBe "v1"
    }
})
