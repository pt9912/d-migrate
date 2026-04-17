package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.1):
 * deterministische Vertragstests fuer den Options-Fingerprint.
 */
class ExportOptionsFingerprintTest : FunSpec({

    fun baseInput(
        format: String = "json",
        encoding: String = "utf-8",
        csvDelimiter: String = ",",
        csvBom: Boolean = false,
        csvNoHeader: Boolean = false,
        csvNullString: String = "",
        filter: String? = null,
        sinceColumn: String? = null,
        since: String? = null,
        tables: List<String> = listOf("users", "orders"),
        outputMode: String = "file-per-table",
        outputPath: String = "/tmp/d-migrate-out",
    ) = ExportOptionsFingerprint.Input(
        format = format,
        encoding = encoding,
        csvDelimiter = csvDelimiter,
        csvBom = csvBom,
        csvNoHeader = csvNoHeader,
        csvNullString = csvNullString,
        filter = filter,
        sinceColumn = sinceColumn,
        since = since,
        tables = tables,
        outputMode = outputMode,
        outputPath = outputPath,
    )

    test("liefert stabilen 64-stelligen Hex-Hash") {
        val hash = ExportOptionsFingerprint.compute(baseInput())
        hash.length shouldBe 64
        hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    test("gleiche Inputs erzeugen gleichen Hash") {
        val a = ExportOptionsFingerprint.compute(baseInput())
        val b = ExportOptionsFingerprint.compute(baseInput())
        a shouldBe b
    }

    test("geaendertes Format erzeugt anderen Hash") {
        val json = ExportOptionsFingerprint.compute(baseInput(format = "json"))
        val yaml = ExportOptionsFingerprint.compute(baseInput(format = "yaml"))
        json shouldNotBe yaml
    }

    test("geaendertes Encoding erzeugt anderen Hash") {
        val utf8 = ExportOptionsFingerprint.compute(baseInput(encoding = "utf-8"))
        val iso = ExportOptionsFingerprint.compute(baseInput(encoding = "iso-8859-1"))
        utf8 shouldNotBe iso
    }

    test("geaenderter CSV-Delimiter erzeugt anderen Hash") {
        val comma = ExportOptionsFingerprint.compute(baseInput(csvDelimiter = ","))
        val semi = ExportOptionsFingerprint.compute(baseInput(csvDelimiter = ";"))
        comma shouldNotBe semi
    }

    test("geaendertes csvBom erzeugt anderen Hash") {
        val off = ExportOptionsFingerprint.compute(baseInput(csvBom = false))
        val on = ExportOptionsFingerprint.compute(baseInput(csvBom = true))
        off shouldNotBe on
    }

    test("geaenderter csvNullString erzeugt anderen Hash") {
        val empty = ExportOptionsFingerprint.compute(baseInput(csvNullString = ""))
        val sentinel = ExportOptionsFingerprint.compute(baseInput(csvNullString = "NULL"))
        empty shouldNotBe sentinel
    }

    test("geaenderter Filter erzeugt anderen Hash") {
        val none = ExportOptionsFingerprint.compute(baseInput(filter = null))
        val active = ExportOptionsFingerprint.compute(baseInput(filter = "id > 42"))
        none shouldNotBe active
    }

    test("geaendertes sinceColumn erzeugt anderen Hash") {
        val a = ExportOptionsFingerprint.compute(baseInput(sinceColumn = null))
        val b = ExportOptionsFingerprint.compute(baseInput(sinceColumn = "updated_at"))
        a shouldNotBe b
    }

    test("geaendertes since erzeugt anderen Hash") {
        val a = ExportOptionsFingerprint.compute(
            baseInput(sinceColumn = "updated_at", since = "2026-01-01"),
        )
        val b = ExportOptionsFingerprint.compute(
            baseInput(sinceColumn = "updated_at", since = "2026-02-01"),
        )
        a shouldNotBe b
    }

    test("geaenderter OutputMode erzeugt anderen Hash") {
        val file = ExportOptionsFingerprint.compute(baseInput(outputMode = "file-per-table"))
        val single = ExportOptionsFingerprint.compute(baseInput(outputMode = "single-file"))
        file shouldNotBe single
    }

    test("geaenderter OutputPath erzeugt anderen Hash (§4.5)") {
        val a = ExportOptionsFingerprint.compute(baseInput(outputPath = "/tmp/a"))
        val b = ExportOptionsFingerprint.compute(baseInput(outputPath = "/tmp/b"))
        a shouldNotBe b
    }

    // §4.2: Tabellenreihenfolge zaehlt — geaenderte Reihenfolge muss
    // einen anderen Hash erzeugen (Ueberplan C.md §4.3:
    // „Tabellenmenge und Reihenfolge").
    test("geaenderte Tabellenreihenfolge erzeugt anderen Hash") {
        val ab = ExportOptionsFingerprint.compute(baseInput(tables = listOf("a", "b")))
        val ba = ExportOptionsFingerprint.compute(baseInput(tables = listOf("b", "a")))
        ab shouldNotBe ba
    }

    test("geaenderte Tabellenmenge erzeugt anderen Hash") {
        val two = ExportOptionsFingerprint.compute(baseInput(tables = listOf("a", "b")))
        val three = ExportOptionsFingerprint.compute(baseInput(tables = listOf("a", "b", "c")))
        two shouldNotBe three
    }

    test("null-Feld vs. wortgleicher String-Wert <null> erzeugen unterschiedliche Hashes") {
        // Null-Marker ist ein interner Sentinel. Ein Filter, der
        // zufaellig genau "<null>" heisst, darf nicht denselben
        // Fingerprint wie das echte null ergeben.
        val real = ExportOptionsFingerprint.compute(baseInput(filter = null))
        val spoof = ExportOptionsFingerprint.compute(baseInput(filter = "<null>"))
        real shouldNotBe spoof
    }
})
