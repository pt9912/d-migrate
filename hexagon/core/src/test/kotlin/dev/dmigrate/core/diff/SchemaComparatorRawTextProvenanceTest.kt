package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Der Vergleich stellt den Dateitext gegen die Katalogform, und die stimmen
 * nie ueberein — der Server druckt aus seinem Parsebaum. Daraus eine Aenderung
 * zu machen hiess, dieselbe bei **jedem** Lauf erneut zu planen.
 *
 * Mit Herkunft lautet die Frage anders: hat der AUTOR den Text seit dem
 * letzten Anwenden geaendert? Das sind zwei Autorentexte, wortgleich
 * vergleichbar.
 */
class SchemaComparatorRawTextProvenanceTest : FunSpec({

    /** Sagt fuer JEDES Feld dasselbe — die Faelle unterscheiden sich nur darin. */
    fun authorship(changed: Boolean?) = RawTextAuthorship { _, _, _, _, _ -> changed }

    fun schema(
        views: Map<String, ViewDefinition> = emptyMap(),
        constraints: List<ConstraintDefinition> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
    ) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
                constraints = constraints,
                indices = indices,
            ),
        ),
        views = views,
    )

    fun check(expression: String) =
        ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = expression)

    // Links steht das Ziel (Katalogform), rechts das Soll (Autorentext) —
    // dieselbe Richtung wie im Migrate-Pfad.
    val catalog = schema(constraints = listOf(check("((age >= 18))")))
    val authored = schema(constraints = listOf(check("age >= 18")))

    test("ohne Herkunft ist die Schreibweise des Servers eine Aenderung — wie bisher") {
        val diff = SchemaComparator().compare(catalog, authored)

        diff.tablesChanged.single().constraintsChanged.shouldNotBeEmptyList()
    }

    test("mit Herkunft, die den Autor entlastet, wird nichts geplant") {
        val diff = SchemaComparator(authorship = authorship(changed = false)).compare(catalog, authored)

        diff.tablesChanged.shouldBeEmpty()
    }

    test("hat der Autor wirklich geaendert, wird geplant") {
        val diff = SchemaComparator(authorship = authorship(changed = true))
            .compare(catalog, schema(constraints = listOf(check("age >= 21"))))

        diff.tablesChanged.single().constraintsChanged.shouldNotBeEmptyList()
    }

    test("ohne Auskunft wird konservativ geplant, nicht geraten") {
        // `null` heisst: keine Herkunft. Der erste Lauf gegen eine bestehende
        // Datenbank fuehrt die Aenderung aus, statt sie liegen zu lassen.
        val diff = SchemaComparator(authorship = authorship(changed = null)).compare(catalog, authored)

        diff.tablesChanged.single().constraintsChanged.shouldNotBeEmptyList()
    }

    test("die gemeldete Aenderung traegt den Autorentext, nicht die gefaltete Form") {
        // Gefaltet wird nur, WORAN verglichen wird. Flosse die Faltung in die
        // Operation, stuende die Katalogform in der erzeugten DDL.
        val diff = SchemaComparator(authorship = authorship(changed = true))
            .compare(catalog, schema(constraints = listOf(check("age >= 21"))))

        diff.tablesChanged.single().constraintsChanged.single().after.expression shouldBe "age >= 21"
    }

    test("ein Sichten-Rumpf ebenso") {
        val left = schema(views = mapOf("v" to ViewDefinition(query = " SELECT\n  age\n FROM orders;")))
        val right = schema(views = mapOf("v" to ViewDefinition(query = "SELECT age FROM orders")))

        SchemaComparator().compare(left, right).viewsChanged.shouldNotBeEmptyList()
        SchemaComparator(authorship = authorship(changed = false)).compare(left, right)
            .viewsChanged.shouldBeEmpty()
    }

    test("Praedikat und Ausdrucks-Schluessel eines Index ebenso") {
        val left = schema(
            indices = listOf(
                IndexDefinition(
                    name = "i", where = "(age > 0)",
                    columns = listOf(IndexColumn(name = "upper(nm::text)", expression = "upper(nm::text)")),
                ),
            ),
        )
        val right = schema(
            indices = listOf(
                IndexDefinition(
                    name = "i", where = "age > 0",
                    columns = listOf(IndexColumn(name = "upper(nm)", expression = "upper(nm)")),
                ),
            ),
        )

        SchemaComparator().compare(left, right).tablesChanged.shouldNotBeEmptyList()
        SchemaComparator(authorship = authorship(changed = false)).compare(left, right)
            .tablesChanged.shouldBeEmpty()
    }

    // ── Sandkasten: die zweite Quelle, wo die Herkunft schweigt ──────────

    /** Sagt fuer jedes Feld dieselbe Serverform. */
    fun sandbox(form: String?) = RawTextServerForm { _, _, _, _ -> form }

    test("ohne Herkunft entscheidet der Sandkasten — zwei Serverformen sind vergleichbar") {
        // Der Autorentext `age >= 18`, vom Server geparst, ergibt genau die
        // Form, die das Ziel fuehrt. Also keine Aenderung.
        val diff = SchemaComparator(authorship = null, serverForm = sandbox("((age >= 18))"))
            .compare(catalog, authored)

        diff.tablesChanged.shouldBeEmpty()
    }

    test("eine andere Serverform ist sehr wohl eine Aenderung") {
        val diff = SchemaComparator(authorship = null, serverForm = sandbox("((age >= 21))"))
            .compare(catalog, authored)

        diff.tablesChanged.single().constraintsChanged.shouldNotBeEmptyList()
    }

    test("die Herkunft hat Vorrang — sie braucht den Server nicht") {
        // Sagt die Herkunft, der Autor habe geaendert, wird geplant, auch wenn
        // der Sandkasten dieselbe Form liefert.
        val diff = SchemaComparator(
            authorship = authorship(changed = true),
            serverForm = sandbox("((age >= 18))"),
        ).compare(catalog, authored)

        diff.tablesChanged.single().constraintsChanged.shouldNotBeEmptyList()
    }

    test("ohne Serverform fuer dieses Feld bleibt es beim Textvergleich") {
        val diff = SchemaComparator(authorship = null, serverForm = sandbox(null))
            .compare(catalog, authored)

        diff.tablesChanged.single().constraintsChanged.shouldNotBeEmptyList()
    }

})

private fun <T> List<T>.shouldNotBeEmptyList() {
    if (isEmpty()) throw AssertionError("expected a non-empty list, but it was empty")
}
