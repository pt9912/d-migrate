package dev.dmigrate.core.diff

import io.kotest.core.spec.style.FunSpec
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Die Kanonisierung roher CHECK-Ausdruecke — **Schreibweise, nicht Bedeutung**.
 *
 * Sie greift nur, wenn der Aufrufer sie anfordert
 * (`SchemaComparator.canonicalizeRawExpressions`): `schema compare` setzt sie,
 * `schema migrate` **nicht**. Der Grund steht in
 * `docs/planning/in-progress/compare-falsch-positive-cross-dialekt.md` — bei
 * `compare` kostet ein Fehlalarm einen Fund, bei `migrate` kostet eine
 * uebersehene Aenderung eine falsch stehende Datenbank.
 *
 * Die Paare unten sind **woertlich** aus einem echten Konsumenten-Vergleich
 * (zwei Reverses desselben Schemas, PostgreSQL gegen SQL Server). Die
 * Grenzfaelle daneben sind der eigentliche Punkt: eine Kanonisierung, die zu
 * viel gleichsetzt, versteckt Unterschiede statt sie zu melden.
 */
class ExpressionCanonicalisationTest : FunSpec({

    // ── Die gemessenen Paare ───────────────────────────────────────

    context("Paare aus dem echten Konsumenten-Vergleich") {

        /**
         * `order_items_quantity_check`. PostgreSQL schreibt `(quantity > 0)`,
         * SQL Server `quantity>(0)` — derselbe Ausdruck, dreimal anders
         * formatiert (Aussenklammern, Whitespace, Klammern um das Literal).
         */
        test("quantity check: PostgreSQL vs SQL Server become equal") {
            ConstraintDiffContract.canonicallyEqual("(quantity > 0)", "quantity>(0)") shouldBe true
        }

        /**
         * `customers_email_check`. PostgreSQL gibt den internen Operator `~~`
         * samt Casts zurueck; die Gegenseite schreibt `like`.
         */
        test("email check: PG's ~~ and ::text are recognised as LIKE") {
            ConstraintDiffContract.canonicallyEqual("(email ~~ '%@%'::text)", "email like '%@%'") shouldBe true
        }
    }

    // ── Die Grenze: was sie NICHT gleichsetzt ──────────────────────

    context("Was ein Parser braeuchte, bleibt Unterschied") {

        test("a different literal stays different") {
            ConstraintDiffContract.canonicallyEqual("age >= 18", "age >= 21") shouldBe false
        }

        test("a different operator stays different") {
            ConstraintDiffContract.canonicallyEqual("age >= 18", "age > 18") shouldBe false
        }

        test("reordered operands stay different") {
            ConstraintDiffContract.canonicallyEqual("a > b", "b < a") shouldBe false
        }

        test("a reordered conjunction stays different") {
            ConstraintDiffContract.canonicallyEqual("a = 1 AND b = 2", "b = 2 AND a = 1") shouldBe false
        }

        /**
         * Der Fall, an dem eine naive Klammer-Regel scheitern wuerde: hier
         * gliedern die Klammern den Ausdruck, sie sind nicht redundant.
         */
        test("grouping parentheses are not stripped") {
            ConstraintDiffContract.canonicallyEqual("(a + b) * c", "a + b * c") shouldBe false
        }

        /** Und im Literal ist `~~` Text, kein Operator. */
        test("an operator-looking sequence inside a literal is left alone") {
            ConstraintDiffContract.canonicallyEqual("note = 'a~~b'", "note = 'a like b'") shouldBe false
        }
    }

    // ── Die Gegenprobe: aendert sie nichts, was sie nicht soll? ────

    test("an expression that is already canonical is unchanged") {
        ConstraintDiffContract.canonicallyEqual("quantity>0", "quantity>0") shouldBe true
    }

    test("a genuinely equal pair needs no canonicalisation") {
        ConstraintDiffContract.canonicallyEqual("status='NEW'", "status='NEW'") shouldBe true
    }

    // ── Der Pfad: das Flag entscheidet ─────────────────────────────

    fun schemaWith(expression: String) = SchemaDefinition(
        name = "app", version = "1.0",
        tables = mapOf(
            "order_items" to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer), "quantity" to ColumnDefinition(NeutralType.Integer)),
                primaryKey = listOf("id"),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "ck_qty", type = ConstraintType.CHECK, expression = expression,
                    ),
                ),
            ),
        ),
    )

    /**
     * Der Kern der Entscheidung: dieselben zwei Schemata, zwei Comparator —
     * und nur der eine meldet. `compare` setzt das Flag, `migrate` nicht.
     */
    test("the same pair is reported by migrate's comparator and not by compare's") {
        val pg = schemaWith("(quantity > 0)")
        val mssql = schemaWith("quantity>(0)")

        // migrate: konservativ — der Unterschied wird gemeldet.
        SchemaComparator().compare(pg, mssql).tablesChanged.shouldNotBeEmpty()
        // compare: die Dialekt-Schreibweise ist keine Aenderung.
        SchemaComparator(canonicalizeRawExpressions = true)
            .compare(pg, mssql).tablesChanged.shouldBeEmpty()
    }

    /** Und die Gegenprobe: ein **echter** Unterschied bleibt auch mit Flag einer. */
    test("a real expression difference is still reported with the flag on") {
        val eighteen = schemaWith("(quantity > 18)")
        val twentyOne = schemaWith("quantity>(21)")

        SchemaComparator(canonicalizeRawExpressions = true)
            .compare(eighteen, twentyOne).tablesChanged.shouldNotBeEmpty()
    }

    context("Identifier-Quoting (Konsumentenbefund gegen 1.7.0)") {

        /** `compare` — mit Kanonisierung. */
        fun compareWith(a: String, b: String) =
            SchemaComparator(canonicalizeRawExpressions = true).compare(schemaWith(a), schemaWith(b))

        test("Oracle's re-quoted identifier is not a change") {
            // d-migrate erzeugt die Differenz selbst: der Requoter quotet die
            // Bezeichner eines CHECK-Ausdrucks fuer Oracle, weil der Server
            // unquotiert auf GROSSSCHREIBUNG faltet. Der Reverse liest nur
            // zurueck, was der Generator geschrieben hat.
            compareWith("(quantity > 0)", "(\"quantity\" > 0)").tablesChanged.shouldBeEmpty()
        }

        test("MySQL's backticks are not a change") {
            compareWith("(quantity > 0)", "(`quantity` > 0)").tablesChanged.shouldBeEmpty()
        }

        test("T-SQL's brackets are not a change") {
            compareWith("(quantity > 0)", "([quantity] > 0)").tablesChanged.shouldBeEmpty()
        }

        test("the spelling is NOT folded: a differently-cased quoted name stays a change") {
            // Die Grenze. Quoting faellt weg, die Schreibweise bleibt — in
            // PostgreSQL sind `"Quantity"` und `quantity` verschiedene Spalten.
            compareWith("(quantity > 0)", "(\"Quantity\" > 0)").tablesChanged.shouldNotBeEmpty()
        }

        test("a bracket that is not an identifier stays: an array index is no quoting") {
            // `[0]` faengt die Regel nicht — sonst wuerde aus einem Array-Index
            // still ein Bezeichner.
            compareWith("(data[0] > 0)", "(data[1] > 0)").tablesChanged.shouldNotBeEmpty()
        }

        test("a quoted string literal is not touched") {
            // Die Literal-Extraktion laeuft vorher; ein Anfuehrungszeichen
            // **im** Literal ist Text.
            compareWith("(note = 'a\"b')", "(note = 'a\"c')").tablesChanged.shouldNotBeEmpty()
        }
    }
})
