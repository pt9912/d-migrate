package dev.dmigrate.core.diff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * **Entscheidungsgrundlage, kein Verhalten.** Hier steht eine Kanonisierung
 * roher CHECK-Ausdruecke, die **nicht** im Produktivpfad haengt — sie misst,
 * was sie am gemeldeten Fall leisten wuerde.
 *
 * Der Grund fuers Messen statt Behauptens: sie kippt eine dokumentierte Linie
 * (`RawTextFolding`: „ohne Herkunft faltet nichts — es bleibt beim
 * Textvergleich, und der plant konservativ"), und fuenf Tests schreiben diese
 * Linie fest. Ob sie weichen soll, ist eine Eigner-Entscheidung — die soll auf
 * Zahlen stehen. Siehe
 * `docs/planning/in-progress/compare-falsch-positive-cross-dialekt.md`.
 *
 * Die Paare unten sind **woertlich** aus einem echten Konsumenten-Vergleich
 * (zwei Reverses desselben Schemas, PostgreSQL gegen SQL Server).
 */
class ExpressionCanonicalisationCandidateTest : FunSpec({

    fun closesAtEnd(e: String): Boolean {
        var depth = 0
        for (i in e.indices) {
            when (e[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && i != e.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    fun String.stripOuterParens(): String {
        var current = this
        while (current.startsWith("(") && current.endsWith(")") && closesAtEnd(current)) {
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    /**
     * Der Kandidat. Bewusst konservativ: er kanonisiert **Schreibweise**, nicht
     * Bedeutung. Was ein Parser braeuchte — vertauschte Operanden, umgestellte
     * Konjunktionen — bleibt ein Unterschied.
     */
    fun canonical(raw: String): String {
        // Platzhalter aus der Private Use Area: kein Leerzeichen (die
        // Whitespace-Normalisierung wuerde ihn zerstoeren), kein Operator, kein
        // Klammerzeichen. Ein " 0 " hier waere der Fehler, der Literale
        // verschluckt und damit echte Unterschiede versteckt.
        val literals = mutableListOf<String>()
        val skeleton = Regex("'(?:[^']|'')*'").replace(raw) { m ->
            literals += m.value
            "\uE000${literals.size - 1}"
        }
        val out = skeleton
            .replace("~~", "like")
            .replace(Regex("::\\s*\"?[A-Za-z_][A-Za-z0-9_]*\"?(\\s*\\([^)]*\\))?"), "")
            .replace(Regex("\\(\\s*([A-Za-z0-9_.$]+)\\s*\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*([=<>!]+|\\*|\\+|-)\\s*"), "$1")
            .trim()
            .stripOuterParens()
            .trim()
        return Regex("\\uE000(\\d+)").replace(out) { literals[it.groupValues[1].toInt()] }
    }

    // ── Die gemessenen Paare ───────────────────────────────────────

    context("Paare aus dem echten Konsumenten-Vergleich") {

        /**
         * `order_items_quantity_check`. PostgreSQL schreibt `(quantity > 0)`,
         * SQL Server `quantity>(0)` — derselbe Ausdruck, dreimal anders
         * formatiert (Aussenklammern, Whitespace, Klammern um das Literal).
         */
        test("quantity check: PostgreSQL vs SQL Server become equal") {
            canonical("(quantity > 0)") shouldBe canonical("quantity>(0)")
        }

        /**
         * `customers_email_check`. PostgreSQL gibt den internen Operator `~~`
         * samt Casts zurueck; die Gegenseite schreibt `like`.
         */
        test("email check: PG's ~~ and ::text are recognised as LIKE") {
            canonical("(email ~~ '%@%'::text)") shouldBe canonical("email like '%@%'")
        }
    }

    // ── Die Grenze: was sie NICHT gleichsetzt ──────────────────────

    context("Was ein Parser braeuchte, bleibt Unterschied") {

        test("a different literal stays different") {
            (canonical("age >= 18") == canonical("age >= 21")) shouldBe false
        }

        test("a different operator stays different") {
            (canonical("age >= 18") == canonical("age > 18")) shouldBe false
        }

        test("reordered operands stay different") {
            (canonical("a > b") == canonical("b < a")) shouldBe false
        }

        test("a reordered conjunction stays different") {
            (canonical("a = 1 AND b = 2") == canonical("b = 2 AND a = 1")) shouldBe false
        }

        /**
         * Der Fall, an dem eine naive Klammer-Regel scheitern wuerde: hier
         * gliedern die Klammern den Ausdruck, sie sind nicht redundant.
         */
        test("grouping parentheses are not stripped") {
            (canonical("(a + b) * c") == canonical("a + b * c")) shouldBe false
        }

        /** Und im Literal ist `~~` Text, kein Operator. */
        test("an operator-looking sequence inside a literal is left alone") {
            (canonical("note = 'a~~b'") == canonical("note = 'a like b'")) shouldBe false
        }
    }

    // ── Die Gegenprobe: aendert sie nichts, was sie nicht soll? ────

    test("an expression that is already canonical is unchanged") {
        canonical("quantity>0") shouldBe "quantity>0"
    }

    test("a genuinely equal pair needs no canonicalisation") {
        canonical("status='NEW'") shouldBe canonical("status='NEW'")
    }
})
