package dev.dmigrate.core.diff

import io.kotest.core.spec.style.FunSpec
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewColumnDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe

/**
 * **Entscheidungsgrundlage, kein Verhalten.** Dieselbe Frage wie bei den
 * CHECK-Ausdruecken, eine Stufe groesser: View-Bodies schreiben die Dialekte
 * verschieden, und der Vergleich meldet das als `VIEW_CHANGED`.
 *
 * Gemessen wird, was eine Kanonisierung leisten **wuerde** — sie haengt nicht
 * im Produktivpfad. Der Grund: View-Text ist laenger und komplexer als ein
 * CHECK-Ausdruck, und je mehr gleichgesetzt wird, desto schwerer faellt ein
 * echter Unterschied auf. Anders als bei `RESTRICT` ist das **keine** Frage,
 * die eine Messung allein entscheidet.
 *
 * Die Paare sind **typische** Reverse-Formen der drei Dialekte, nicht
 * woertlich aus einem Konsumenten-Vergleich — dort stand nur „VIEW_CHANGED
 * durch Dialekt-Text" ohne Text.
 */
class ViewQueryCanonicalisationTest : FunSpec({

    fun view(query: String) = SchemaDefinition(
        name = "app", version = "1.0",
        views = mapOf("v" to ViewDefinition(query = query)),
    )

    /** `schema compare` — mit Kanonisierung. */
    fun compareWith(queryA: String, queryB: String) =
        SchemaComparator(canonicalizeRawExpressions = true).compare(view(queryA), view(queryB))

    /** `schema migrate` — ohne. */
    fun compareWithout(queryA: String, queryB: String) =
        SchemaComparator().compare(view(queryA), view(queryB))

    context("Dieselbe Sicht in den Schreibweisen der drei Dialekte") {

        test("PostgreSQL, MySQL and SQL Server are not a change with the flag on") {
            val pg = "SELECT o.id, c.email FROM orders o JOIN customers c ON c.id = o.customer_id"
            val my = "SELECT `o`.`id`, `c`.`email` FROM `orders` `o` JOIN `customers` `c` " +
                "ON `c`.`id` = `o`.`customer_id`"
            val ms = "SELECT [o].[id], [c].[email] FROM [orders] [o] JOIN [customers] [c] " +
                "ON [c].[id] = [o].[customer_id]"

            compareWith(pg, my).viewsChanged.shouldBeEmpty()
            compareWith(pg, ms).viewsChanged.shouldBeEmpty()
        }

        /**
         * **Der Pfad-Kern.** Dieselben zwei Sichten, zwei Comparator: nur der
         * von `compare` schweigt. `migrate` bleibt konservativ — dort kostet
         * eine uebersehene Aenderung eine falsch stehende Datenbank.
         */
        test("the same pair is reported by migrate's comparator and not by compare's") {
            val pg = "SELECT id FROM orders"
            val my = "SELECT `id` FROM `orders`"

            compareWithout(pg, my).viewsChanged.shouldNotBeEmpty()
            compareWith(pg, my).viewsChanged.shouldBeEmpty()
        }
    }

    context("Was die Struktur betrifft, bleibt Unterschied") {

        test("a different selected column stays different") {
            compareWith("SELECT o.id FROM orders o", "SELECT o.total FROM orders o")
                .viewsChanged.shouldNotBeEmpty()
        }

        test("a different table stays different") {
            compareWith("SELECT id FROM orders", "SELECT id FROM customers")
                .viewsChanged.shouldNotBeEmpty()
        }

        test("an added WHERE clause stays different") {
            compareWith("SELECT id FROM orders", "SELECT id FROM orders WHERE status = 'NEW'")
                .viewsChanged.shouldNotBeEmpty()
        }

        test("swapped columns stay different") {
            compareWith("SELECT a, b FROM t", "SELECT b, a FROM t").viewsChanged.shouldNotBeEmpty()
        }
    }

    context("Das abschliessende Semikolon") {

        test("the server's trailing semicolon is not a change") {
            // `pg_get_viewdef` haengt ihn an, der Autor nicht — im Round-Trip
            // Quelle gegen Reverse war das ein `VIEW_CHANGED` ohne Grund.
            compareWith("SELECT id FROM orders", "SELECT id FROM orders;").viewsChanged.shouldBeEmpty()
            compareWith(" SELECT id\n   FROM orders;", "SELECT id FROM orders").viewsChanged.shouldBeEmpty()
        }

        test("mehrere abschliessende Semikola sind auch keine Aenderung") {
            // Gemessen am SQL-Server-Reverse: der Server haengt dem
            // gespeicherten Definitionstext seines an, und trug die
            // angewendete DDL schon eines, stehen dort **zwei**
            // (`…customer_id;;`). Ein einzelnes `removeSuffix` liesse einen
            // Rest stehen — der Fehlalarm waere nur um eine Runde verschoben.
            val pg = "SELECT o.id FROM orders o JOIN customers c ON c.id = o.customer_id;"
            val mssql = " SELECT o.id\n   FROM orders o\n     JOIN customers c ON c.id = o.customer_id;;"
            compareWith(pg, mssql).viewsChanged.shouldBeEmpty()
            compareWith("SELECT id FROM orders", "SELECT id FROM orders;;;").viewsChanged.shouldBeEmpty()
        }

        test("a semicolon inside a literal is text, not a terminator") {
            // Nur das **abschliessende** faellt weg.
            compareWith("SELECT id FROM orders WHERE note = 'a;b'", "SELECT id FROM orders")
                .viewsChanged.shouldNotBeEmpty()
        }

        test("two statements stay different") {
            compareWith("SELECT id FROM t; SELECT id FROM t", "SELECT id FROM t")
                .viewsChanged.shouldNotBeEmpty()
        }
    }

    context("Die abgeleiteten Spalten (Konsumentenbefund gegen 1.7.0)") {

        fun viewWith(query: String, columns: List<Pair<String, String?>>?) = SchemaDefinition(
            name = "app", version = "1.0",
            views = mapOf(
                "v" to ViewDefinition(
                    query = query,
                    columns = columns?.map { (n, t) -> ViewColumnDefinition(n, t) },
                ),
            ),
        )

        fun compareViews(l: SchemaDefinition, r: SchemaDefinition) =
            SchemaComparator(canonicalizeRawExpressions = true).compare(l, r)

        test("identical query AND identical columns is no change — the control") {
            val q = "SELECT id FROM orders"
            compareViews(viewWith(q, listOf("id" to "integer")), viewWith(q, listOf("id" to "integer")))
                .viewsChanged.shouldBeEmpty()
        }

        test("identical query but different column TYPES is no change") {
            // Der Typ ist Dialekt-Schreibweise: `text` gegen `nvarchar`,
            // `numeric(10,2)` gegen `decimal`. Der SQL-Server-Fall des
            // Konsumenten war genau das — die Query war byte-identisch.
            val q = "SELECT id FROM orders"
            compareViews(viewWith(q, listOf("id" to "text")), viewWith(q, listOf("id" to "nvarchar")))
                .viewsChanged.shouldBeEmpty()
        }

        test("one side without columns is no change either") {
            // MySQL und Oracle liefern gar keine; einseitiges Fehlen ist eine
            // Datenluecke beim Lesen, keine Schemaaenderung.
            val q = "SELECT id FROM orders"
            compareViews(viewWith(q, listOf("id" to "integer")), viewWith(q, null))
                .viewsChanged.shouldBeEmpty()
        }

        test("a different column NAME is a change and carries both sides") {
            // Und wenn wirklich etwas anders ist, steht es im Fund — statt
            // eines blanken `VIEW_CHANGED` ohne Feldzeile.
            val q = "SELECT id FROM orders"
            val diff = compareViews(
                viewWith(q, listOf("id" to "integer")),
                viewWith(q, listOf("total" to "integer")),
            ).viewsChanged.single()
            diff.columns?.before shouldBe listOf("id")
            diff.columns?.after shouldBe listOf("total")
        }
    }
})
