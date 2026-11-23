package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * ADR 0067 / Issue #80: `schema compare` (Dialekt-Schreibweise gefaltet) wertet einen
 * Wertelisten-CHECK mit gleichem Wertevorrat derselben Spalte als unveraendert, gleich in welcher
 * Form der Server ihn zurueckgibt und in welcher Reihenfolge. Ein anderer Wertevorrat und jede
 * Form ausserhalb der Werteliste-Erkennung bleiben ein Unterschied.
 */
class CompareValueListCheckTest : FunSpec({

    fun schema(expression: String, extraColumn: String? = null) = SchemaDefinition(
        name = "repro", version = "1.0.0",
        tables = mapOf(
            "obj_device" to TableDefinition(
                columns = buildMap {
                    put("id", ColumnDefinition(NeutralType.Uuid, required = true))
                    put("a_state", ColumnDefinition(NeutralType.Text()))
                    extraColumn?.let { put(it, ColumnDefinition(NeutralType.Text())) }
                },
                primaryKey = listOf("id"),
                constraints = listOf(
                    ConstraintDefinition(name = "ck_a_state", type = ConstraintType.CHECK, expression = expression),
                ),
            ),
        ),
    )

    fun changed(file: String, db: String, extraColumn: String? = null): Boolean =
        SchemaComparator(canonicalizeRawExpressions = true)
            .compare(schema(file, extraColumn), schema(db, extraColumn))
            .tablesChanged.isNotEmpty()

    val file = "a_state IN ('OFF', 'ON')"

    val sameVocabulary = listOf(
        "PostgreSQL-Rueckform (Issue #80)" to "(a_state = ANY (ARRAY['OFF'::text, 'ON'::text]))",
        "andere Reihenfolge" to "a_state IN ('ON', 'OFF')",
        "SQL-Server-OR-Kette, umsortiert" to "([a_state]='ON' OR [a_state]='OFF')",
    )
    for ((label, db) in sameVocabulary) {
        test("gleicher Wertevorrat ist keine Aenderung: $label") {
            withClue(db) { changed(file, db) shouldBe false }
        }
    }

    test("einelementige Liste: PostgreSQL schreibt `=` statt `IN` (gemessen: `(e = 'ON'::text)`)") {
        changed("a_state IN ('ON')", "(a_state = 'ON'::text)") shouldBe false
    }

    test("ein Literal, das `::` enthaelt, ist kein Cast") {
        changed("a_state IN ('a::character', 'ON')", "(a_state = ANY (ARRAY['ON'::text, 'a::character'::text]))") shouldBe false
    }

    val different = listOf(
        "ein Wert mehr" to "(a_state = ANY (ARRAY['OFF'::text, 'ON'::text, 'BROKEN'::text]))",
        "ein Wert weniger" to "(a_state = ANY (ARRAY['ON'::text]))",
        "anderer Wert" to "a_state IN ('OFF', 'STANDBY')",
        "NOT IN ist keine Werteliste" to "a_state NOT IN ('OFF', 'ON')",
        "mit AND verknuepft ist keine Werteliste" to "a_state IN ('OFF', 'ON') AND a_state <> ''",
        "Cast auf character (= char(1)) aendert den Wert" to "a_state IN ('OFF'::character, 'ON'::character)",
        "Cast mit Laenge kann kuerzen" to "a_state IN ('OFF'::character varying(2), 'ON'::character varying(2))",
        // Gemessen an PostgreSQL 18: so kommt `IN` an einer varchar-Spalte zurueck. Die
        // Werteliste-Erkennung kennt die Array-Form mit `::text[]` nicht; das ist ihre Grenze,
        // nicht die dieser Regel (ADR 0067).
        "varchar-Rueckform ausserhalb der Erkennung" to
            "((a_state)::text = ANY ((ARRAY['OFF'::character varying, 'ON'::character varying])::text[]))",
    )
    for ((label, db) in different) {
        test("bleibt eine Aenderung: $label") {
            withClue(db) { changed(file, db) shouldBe true }
        }
    }

    test("an einer char(n)-Spalte bleibt es beim Textvergleich: PostgreSQL vergleicht aufgefuellt") {
        fun charSchema(expression: String) = SchemaDefinition(
            name = "repro", version = "1.0.0",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = mapOf("d" to ColumnDefinition(NeutralType.Char(length = 3))),
                    constraints = listOf(
                        ConstraintDefinition(name = "ck_d", type = ConstraintType.CHECK, expression = expression),
                    ),
                ),
            ),
        )
        SchemaComparator(canonicalizeRawExpressions = true)
            .compare(charSchema("d IN ('OFF', 'ON')"), charSchema("(d = ANY (ARRAY['OFF'::bpchar, 'ON'::bpchar]))"))
            .tablesChanged.isNotEmpty() shouldBe true
    }

    test("dieselben Werte an einer anderen Spalte bleiben eine Aenderung") {
        changed(file, "(b_state = ANY (ARRAY['OFF'::text, 'ON'::text]))", extraColumn = "b_state") shouldBe true
    }

    test("schema migrate (ohne Schreibweise-Faltung) ist von dieser Regel nicht betroffen") {
        // Der strikte Vergleich ohne Faltung bleibt beim Textvergleich; den Wertevorrat behandelt
        // dort der zielbewusste Vergleich (ADR 0055), nicht diese Regel.
        SchemaComparator().compare(schema(file), schema("a_state IN ('ON', 'OFF')"))
            .tablesChanged.isNotEmpty() shouldBe true
    }

    test("ein gemeldeter Fund traegt die unveraenderten Ausdruecke beider Seiten") {
        val diff = SchemaComparator(canonicalizeRawExpressions = true)
            .compare(schema(file), schema("a_state IN ('OFF', 'STANDBY')"))
        val change = diff.tablesChanged.single().constraintsChanged.single()
        change.before.expression shouldBe file
        change.after.expression shouldBe "a_state IN ('OFF', 'STANDBY')"
    }
})
