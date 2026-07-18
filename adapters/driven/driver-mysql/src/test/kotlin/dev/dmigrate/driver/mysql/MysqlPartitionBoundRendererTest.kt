package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Wert-Seite der MySQL-Partitionsgrenzen (AP6-Review P1 #1 / P2 #2,#3 / Altitude #11).
 * Direkt-Tests der strukturierten Temporal-Behandlung, inkl. der Zweige, die der
 * Generate-Pfad nicht ohnehin abdeckt (Pass-Through unerkennbarer Formen).
 */
class MysqlPartitionBoundRendererTest : FunSpec({

    val renderer = MysqlPartitionBoundRenderer()

    fun render(literal: String, keyType: NeutralType?): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        val out = renderer.renderColumnBoundLiteral(literal, keyType, "p", notes, mutableSetOf())
        return out to notes
    }

    test("isTemporal recognizes DATE and DATETIME only") {
        renderer.isTemporal(NeutralType.Date) shouldBe true
        renderer.isTemporal(NeutralType.DateTime(timezone = true)) shouldBe true
        renderer.isTemporal(NeutralType.Integer) shouldBe false
        renderer.isTemporal(null) shouldBe false
    }

    test("non-temporal literal is returned verbatim (no quoting)") {
        render("100", NeutralType.Integer) shouldBe ("100" to emptyList())
    }

    test("date-only bound keeps the full date and adds no note (#1)") {
        render("'2024-02-29'", NeutralType.Date) shouldBe ("'2024-02-29'" to emptyList())
    }

    test("an unquoted bound is left unchanged — the model carries the quotes, not the renderer (contract, #3/C)") {
        // Quoting is a fixture/spec contract, identical for both dialects; the renderer must NOT
        // add quotes (that would make the same neutral schema valid for MySQL but invalid for PG).
        render("2025-01-01", NeutralType.Date) shouldBe ("2025-01-01" to emptyList())
    }

    test("UTC offset is stripped and W129 emitted") {
        val (out, notes) = render("'2022-02-01 00:00:00+00'", NeutralType.DateTime(timezone = true))
        out shouldBe "'2022-02-01 00:00:00'"
        notes.single().code shouldBe "W129"
        notes.single().type shouldBe NoteType.WARNING
    }

    test("Zulu 'Z' suffix is recognized as UTC and stripped (#B)") {
        val (out, notes) = render("'2022-02-01 00:00:00Z'", NeutralType.DateTime(timezone = true))
        out shouldBe "'2022-02-01 00:00:00'"
        notes.single().code shouldBe "W129"
    }

    test("ISO 'T' separator and fractional seconds normalize to a space-separated instant") {
        render("'2022-02-01T12:30:00.500+00:00'", NeutralType.DateTime()).first shouldBe "'2022-02-01 12:30:00.500'"
    }

    test("lowercase 't' separator is accepted (#B)") {
        render("'2022-02-01t12:30:00+00'", NeutralType.DateTime()).first shouldBe "'2022-02-01 12:30:00'"
    }

    test("non-UTC offset keeps the bound unchanged and emits E061 (#2)") {
        val (out, notes) = render("'2022-02-01 00:00:00-05'", NeutralType.DateTime(timezone = true))
        out shouldBe "'2022-02-01 00:00:00-05'"
        notes.single().code shouldBe "E061"
        notes.single().type shouldBe NoteType.ACTION_REQUIRED
    }

    test("an unrecognized temporal form is passed through unchanged (no guessing)") {
        render("'not-a-date'", NeutralType.DateTime()) shouldBe ("'not-a-date'" to emptyList())
    }

    test("a string bound ending in backslash gets the backslash doubled for MySQL (CWE-89)") {
        // The model carries the literal already quoted ('a\'). MySQL treats a lone trailing
        // backslash as escaping the closing quote, so the value would break out of its
        // VALUES IN (…) / VALUES LESS THAN (…) clause. MySQL needs the backslash doubled;
        // PG (standard_conforming_strings) does not, which is why this is a MySQL-render concern.
        render("'a\\'", null) shouldBe ("'a\\\\'" to emptyList())
    }

    test("an embedded backslash in a string bound is doubled for MySQL") {
        render("'a\\b'", null) shouldBe ("'a\\\\b'" to emptyList())
    }

    test("SQL-standard doubled quotes are preserved — only the backslash is MySQL-escaped") {
        // '' is a valid embedded quote for MySQL too; the fix must not re-touch it (that would
        // corrupt the value), only layer backslash doubling on top of the existing quoting.
        render("'a''b'", null) shouldBe ("'a''b'" to emptyList())
    }

    test("a backslash directly adjacent to a doubled quote is escaped without corrupting the quote") {
        // Value a\'b → model literal 'a\''b'. Only the backslash is doubled; the '' pair stays intact,
        // so MySQL reads the value back as a\'b. This is the trickiest interaction of the two escapings.
        render("'a\\''b'", null) shouldBe ("'a\\\\''b'" to emptyList())
    }

    test("a numeric (unquoted) bound is never backslash-touched") {
        render("100", NeutralType.Integer) shouldBe ("100" to emptyList())
    }

    test("a temporal bound normalized to UTC is still backslash-safe (no backslash → unchanged)") {
        val (out, notes) = render("'2022-02-01 00:00:00+00'", NeutralType.DateTime(timezone = true))
        out shouldBe "'2022-02-01 00:00:00'"
        notes.single().code shouldBe "W129"
    }
})
