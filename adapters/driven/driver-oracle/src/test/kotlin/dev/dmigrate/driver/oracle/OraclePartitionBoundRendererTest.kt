package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OraclePartitionBoundRendererTest : FunSpec({

    val renderer = OraclePartitionBoundRenderer()

    fun render(literal: String, type: NeutralType?): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        return renderer.render(literal, type, "p1", notes, mutableSetOf()) to notes
    }

    test("a date bound gets an explicit mask, because a bare literal follows NLS_DATE_FORMAT") {
        // Live belegt: ohne die Umsetzung antwortet Oracle mit ORA-01861.
        render("'2024-01-01'", NeutralType.Date).first shouldBe "TO_DATE('2024-01-01', 'YYYY-MM-DD')"
    }

    test("the mask follows the shape of the literal, not the column type") {
        // Eine Zeitgrenze mit der Datumsmaske zu lesen schnitte den Zeitanteil
        // still ab; eine Datumsgrenze mit der Zeitmaske scheiterte.
        render("'2024-01-01 12:30:00'", NeutralType.Date).first shouldBe
            "TO_DATE('2024-01-01 12:30:00', 'YYYY-MM-DD HH24:MI:SS')"
    }

    test("a sub-second bound is kept unconverted, so the statement fails instead of shifting") {
        // Live gemessen: Oracle nimmt TO_TIMESTAMP(…'.5') gegen eine
        // DATE-Spalte an und schneidet die Bruchteilsekunde ab -- die Grenze
        // verschiebt sich still.
        val (sql, notes) = render("'2024-01-01 12:30:00.5'", NeutralType.DateTime(timezone = false))
        sql shouldBe "'2024-01-01 12:30:00.5'"
        notes.single { it.code == "E061" }.message shouldContain "sub-second"
    }

    test("a non-temporal bound passes through untouched — the model carries its own quoting") {
        render("'A'", NeutralType.Text(maxLength = 10)).first shouldBe "'A'"
        render("42", NeutralType.Integer).first shouldBe "42"
    }

    test("an unrecognised temporal form is left alone rather than guessed at") {
        render("SYSDATE", NeutralType.Date).first shouldBe "SYSDATE"
    }

    test("a UTC offset is stripped and reported") {
        val (sql, notes) = render("'2024-01-01 00:00:00Z'", NeutralType.DateTime(timezone = false))
        sql shouldBe "TO_DATE('2024-01-01 00:00:00', 'YYYY-MM-DD HH24:MI:SS')"
        notes.single { it.code == "W129" }.message shouldContain "no time zone"
    }

    test("a non-UTC offset is kept, so the DDL fails loudly instead of shifting the boundary") {
        val (sql, notes) = render("'2024-01-01 00:00:00+02:00'", NeutralType.DateTime(timezone = false))
        sql shouldBe "'2024-01-01 00:00:00+02:00'"
        notes.single { it.code == "E061" }.message shouldContain "non-UTC timezone offset"
    }

    test("the W129 note is emitted once per table, not once per partition") {
        val notes = mutableListOf<TransformationNote>()
        val emitted = mutableSetOf<String>()
        repeat(3) {
            renderer.render("'2024-01-01 00:00:00Z'", NeutralType.Date, "p$it", notes, emitted)
        }
        notes.count { it.code == "W129" } shouldBe 1
    }
})
