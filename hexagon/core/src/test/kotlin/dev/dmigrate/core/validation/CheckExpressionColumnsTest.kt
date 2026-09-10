package dev.dmigrate.core.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Was in einem CHECK-Ausdruck ein Spaltenbezug ist — und was nur so aussieht.
 *
 * Die Regel darueber entscheidet, ob ein Schema angenommen wird (E012 ist ein
 * Fehler, kein Hinweis). Ein Fehlalarm haelt deshalb gueltiges SQL an; diese
 * Faelle stehen hier alle, weil sie zuvor genau das taten.
 */
class CheckExpressionColumnsTest : FunSpec({

    fun columnsIn(expression: String) = CheckExpressionColumns.referencedIn(expression)

    test("ein einfacher Vergleich nennt seine Spalte") {
        columnsIn("total >= 0") shouldBe listOf("total")
        columnsIn("qty * price >= 0") shouldBe listOf("qty", "price")
    }

    test("Zeichenketten sind keine Spalten") {
        columnsIn("status IN ('active', 'deleted')") shouldBe listOf("status")
    }

    test("ein Funktionsname ist keine Spalte") {
        columnsIn("LENGTH(name) > 3") shouldBe listOf("name")
        columnsIn("COALESCE(name, '') <> ''") shouldBe listOf("name")
        columnsIn("upper(status) = status") shouldBe listOf("status")
    }

    test("ein Typname hinter :: ist keine Spalte — auch mehrwortig") {
        columnsIn("amount > 0::numeric") shouldBe listOf("amount")
        columnsIn("name::character varying = other") shouldBe listOf("name", "other")
        columnsIn("ts::timestamp with time zone > x") shouldBe listOf("ts", "x")
    }

    test("ein Typname hinter AS ist keine Spalte — auch mehrwortig") {
        columnsIn("CAST(x AS integer) > 0") shouldBe listOf("x")
        columnsIn("CAST(x AS double precision) > 0") shouldBe listOf("x")
    }

    test("die Katalogform eines IN-Ausdrucks nennt nur ihre eine Spalte") {
        // Genau die Form, die PostgreSQL aus `operation IN (...)` macht. Sie
        // wortgleich in eine Schemadatei zu schreiben war zuvor unmoeglich:
        // `text`, `ARRAY`, `character` und `varying` galten als Spalten.
        val catalogForm = "((operation)::text = ANY ((ARRAY['INSERT'::character varying, " +
            "'UPDATE'::character varying, 'DELETE'::character varying])::text[]))"

        columnsIn(catalogForm) shouldBe listOf("operation")
    }

    test("EXTRACT nennt ein Feld, keine Spalte") {
        columnsIn("EXTRACT(YEAR FROM created_at) > 2000") shouldBe listOf("created_at")
    }

    test("eine parameterlose Standardfunktion ist keine Spalte") {
        columnsIn("created_at < CURRENT_DATE") shouldBe listOf("created_at")
        columnsIn("created_at < LOCALTIMESTAMP") shouldBe listOf("created_at")
    }

    test("bei einer Qualifizierung zaehlt der Teil hinter dem Punkt") {
        columnsIn("t.status <> ''") shouldBe listOf("status")
    }

    test("ein wirklich unbekannter Bezeichner bleibt sichtbar — sonst faenge die Regel nichts mehr") {
        columnsIn("statsu > 0") shouldBe listOf("statsu")
        columnsIn("LENGTH(naem) > 3") shouldBe listOf("naem")
    }

    test("ein Ausdruck ohne Bezeichner nennt nichts") {
        columnsIn("1 = 1").shouldBeEmpty()
        columnsIn("'a' <> 'b'").shouldBeEmpty()
    }
})
