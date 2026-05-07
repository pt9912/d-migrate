package dev.dmigrate.mcp.upload.bundle

import dev.dmigrate.server.core.upload.bundle.BundleFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Follow-up AP 2 — Acceptance-Tests für [BundleManifestParser].
 *
 * Plan §4 fordert stabile Wire-Codes für jede Manifest-Sicherheitsfehler.
 * Diese Tests sichern: kein Code-Pfad führt zu IllegalArgumentException
 * (alle Fehler werden als `Invalid` gemeldet), und alle Plan-§4-
 * Sicherheitsfälle werden abgewiesen.
 */
class BundleManifestParserTest : FunSpec({

    val limit = 1L * 1024 * 1024 // 1 MiB

    fun parseJson(json: String) = BundleManifestParser.parse(json.toByteArray(Charsets.UTF_8), limit)

    test("Gültiges v1-Manifest mit zwei Tabellen wird akzeptiert") {
        val outcome = parseJson(
            """
            {
              "version": "v1",
              "format": "csv",
              "tables": [
                {"name": "users",  "path": "users.csv"},
                {"name": "orders", "path": "orders.csv"}
              ]
            }
            """.trimIndent(),
        )
        val valid = outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Valid>()
        valid.manifest.version shouldBe "v1"
        valid.manifest.format shouldBe "csv"
        valid.manifest.tables.size shouldBe 2
        valid.manifest.tables[0].name shouldBe "users"
        valid.manifest.tables[1].path shouldBe "orders.csv"
    }

    test("Manifest >1 MiB wird abgewiesen") {
        val big = ByteArray((limit + 1).toInt())
        val outcome = BundleManifestParser.parse(big, limit)
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "exceeds size limit"
    }

    test("Leeres Manifest wird abgewiesen") {
        val outcome = BundleManifestParser.parse(ByteArray(0), limit)
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "empty"
    }

    test("Nicht-JSON wird abgewiesen") {
        val outcome = parseJson("this is not json {")
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "not valid JSON"
    }

    test("JSON-Array statt Object wird abgewiesen") {
        val outcome = parseJson("[]")
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "must be a JSON object"
    }

    test("Falsche version wird abgewiesen") {
        val outcome = parseJson(
            """
            {"version":"v2","format":"csv","tables":[{"name":"a","path":"a.csv"}]}
            """.trimIndent(),
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain BundleFormat.MANIFEST_VERSION_V1
    }

    test("Fehlendes format wird abgewiesen") {
        val outcome = parseJson(
            """
            {"version":"v1","tables":[{"name":"a","path":"a.csv"}]}
            """.trimIndent(),
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "format"
    }

    test("Leere tables-Liste wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":[]}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "must not be empty"
    }

    test("tables ohne Array-Form wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":"users"}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "tables must be an array"
    }

    test("Absoluter Pfad wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":[{"name":"a","path":"/etc/passwd"}]}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "absolute path"
    }

    test("Backslash im Pfad wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":[{"name":"a","path":"win\\file.csv"}]}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "backslash"
    }

    test("'..'-Segment im Pfad wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":[{"name":"a","path":"../escape.csv"}]}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "'..' segment"
    }

    test("Doppelter Tabellenname wird abgewiesen") {
        val outcome = parseJson(
            """
            {"version":"v1","format":"csv","tables":[
              {"name":"a","path":"a.csv"},
              {"name":"a","path":"b.csv"}
            ]}
            """.trimIndent(),
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "duplicate name"
    }

    test("Doppelter Pfad case-insensitive wird abgewiesen") {
        val outcome = parseJson(
            """
            {"version":"v1","format":"csv","tables":[
              {"name":"a","path":"users.csv"},
              {"name":"b","path":"Users.csv"}
            ]}
            """.trimIndent(),
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "duplicate path"
    }

    test("Tabellen-Eintrag ohne name wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":[{"path":"a.csv"}]}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "non-blank 'name'"
    }

    test("Tabellen-Eintrag mit Number-Form wird abgewiesen") {
        val outcome = parseJson(
            """{"version":"v1","format":"csv","tables":[42]}""",
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "tables[] must contain objects"
    }

    test("Unbekannte Top-Level-Keys werden abgewiesen") {
        val outcome = parseJson(
            """
            {"version":"v1","format":"csv","extra":"hi","tables":[{"name":"a","path":"a.csv"}]}
            """.trimIndent(),
        )
        outcome.shouldBeInstanceOf<BundleManifestParseOutcome.Invalid>()
            .reason shouldContain "unsupported top-level keys"
    }

    test("Fingerprint ist deterministisch und SHA-256 hex (64 Zeichen)") {
        val bytes = """{"version":"v1"}""".toByteArray(Charsets.UTF_8)
        val fp1 = BundleManifestParser.fingerprint(bytes)
        val fp2 = BundleManifestParser.fingerprint(bytes)
        fp1 shouldBe fp2
        fp1.length shouldBe 64
        fp1.all { it.isDigit() || it in 'a'..'f' } shouldBe true
    }
})
