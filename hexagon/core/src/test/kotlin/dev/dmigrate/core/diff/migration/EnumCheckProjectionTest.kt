package dev.dmigrate.core.diff.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Der Erkenner fuer `<spalte> IN (…)`. Er entscheidet, welche CHECKs im
 * Fingerprint als Wertevorrat gelten — zu weit gefasst faltet er echte
 * Constraints weg, zu eng bleibt der Enum-Drift bestehen.
 */
class EnumCheckProjectionTest : FunSpec({

    test("the plain form is recognised") {
        EnumCheckProjection.valuesOf("mood IN ('red', 'green')", "mood") shouldBe listOf("red", "green")
    }

    test("the dialects' quoting styles are all accepted") {
        // Der Reverse liefert je nach Dialekt eine andere Schreibweise.
        listOf("\"mood\"", "[mood]", "`mood`").forEach { quoted ->
            EnumCheckProjection.valuesOf("$quoted IN ('red')", "mood") shouldBe listOf("red")
        }
    }

    test("surrounding parentheses do not hide the form") {
        EnumCheckProjection.valuesOf("(mood IN ('red'))", "mood") shouldBe listOf("red")
    }

    test("a comma INSIDE a value does not split the list") {
        EnumCheckProjection.valuesOf("mood IN ('red, green', 'blue')", "mood") shouldBe
            listOf("red, green", "blue")
    }

    test("a doubled quote is a character, not the end of the value") {
        EnumCheckProjection.valuesOf("mood IN ('it''s')", "mood") shouldBe listOf("it's")
    }

    test("a check over a DIFFERENT column is not a match") {
        EnumCheckProjection.valuesOf("other IN ('red')", "mood") shouldBe null
    }

    test("a column name that merely starts with the same letters is not a match") {
        EnumCheckProjection.valuesOf("moody IN ('red')", "mood") shouldBe null
    }

    test("NOT IN is not the same statement") {
        EnumCheckProjection.valuesOf("mood NOT IN ('red')", "mood") shouldBe null
    }

    test("anything other than string literals disqualifies the list") {
        EnumCheckProjection.valuesOf("mood IN ('red', 42)", "mood") shouldBe null
        EnumCheckProjection.valuesOf("mood IN ('red', other)", "mood") shouldBe null
        EnumCheckProjection.valuesOf("mood IN ()", "mood") shouldBe null
    }

    test("a longer expression that merely CONTAINS the form is not a match") {
        EnumCheckProjection.valuesOf("mood IN ('red') AND active = 1", "mood") shouldBe null
    }

    test("a null expression is not a match") {
        EnumCheckProjection.valuesOf(null, "mood") shouldBe null
    }

    // Die Form, in der SQL Server den Ausdruck tatsaechlich zurueckliefert —
    // live gemessen, nicht angenommen. Genau daran ist die erste Fassung
    // dieses Erkenners vorbeigelaufen.

    test("the OR-chain form SQL Server stores is recognised") {
        EnumCheckProjection.valuesOf("mood='green' OR mood='red'", "mood") shouldBe listOf("green", "red")
    }

    test("whitespace around the equals sign does not matter") {
        EnumCheckProjection.valuesOf("mood = 'green' or mood = 'red'", "mood") shouldBe listOf("green", "red")
    }

    test("a single equality is a one-value set") {
        EnumCheckProjection.valuesOf("mood='red'", "mood") shouldBe listOf("red")
    }

    test("an OR inside a value does not split the chain") {
        EnumCheckProjection.valuesOf("mood='a OR b'", "mood") shouldBe listOf("a OR b")
    }

    test("an AND chain is something else entirely") {
        EnumCheckProjection.valuesOf("mood='red' AND active='1'", "mood") shouldBe null
    }

    test("a chain over two DIFFERENT columns is not a value set") {
        EnumCheckProjection.valuesOf("mood='red' OR other='green'", "mood") shouldBe null
    }

    test("a comparison against something other than a literal is not a match") {
        EnumCheckProjection.valuesOf("mood=other", "mood") shouldBe null
        EnumCheckProjection.valuesOf("mood>'red'", "mood") shouldBe null
    }

    test("each side of the chain may carry its own parentheses") {
        // Blindes Abschneiden des ersten und letzten Zeichens verstuemmelte
        // diesen Ausdruck frueher zu etwas Unlesbarem.
        EnumCheckProjection.valuesOf("(mood='a') OR (mood='b')", "mood") shouldBe listOf("a", "b")
    }

    test("nested outer parentheses are peeled, unbalanced ones are not") {
        EnumCheckProjection.valuesOf("((mood='a'))", "mood") shouldBe listOf("a")
    }
})
