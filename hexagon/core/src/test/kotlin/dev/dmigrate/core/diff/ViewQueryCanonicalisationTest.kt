package dev.dmigrate.core.diff

import io.kotest.core.spec.style.FunSpec
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty

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
})
