package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Eine berechnete Spalte kommt bei SQL Server als berechnete zurueck.
 *
 * Was kein Unit-Test zeigen kann: die Form, in der der Server den Ausdruck
 * fuehrt. `sys.computed_columns.definition` liefert ihn normalisiert und in
 * eckigen Klammern — gemessen auf 2025 wird aus `qty * price` die Form
 * `([qty]*[price])`, und aus einem `CAST` ein `CONVERT`. Der Autorentext kommt
 * also nie zurueck, und genau deshalb faellt der Ausdruck aus dem
 * Textvergleich heraus.
 *
 * Ins **Modell** geht er seit P12 in neutraler Schreibweise: das
 * Klammer-Quoting weicht dem nackten Namen bzw. `"Name"`, und die aeussere
 * Klammer des Servers faellt weg. Ohne das lehnte jedes andere Ziel die
 * erzeugte DDL ab (PostgreSQL: `syntax error at or near "["`).
 *
 * `is_persisted` unterscheidet die Speicherform: `PERSISTED` liegt gespeichert,
 * ohne Angabe wird bei jedem Zugriff gerechnet.
 */
class MssqlComputedColumnReverseIntegrationTest : FunSpec({

    val container = startMssqlContainer()
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = poolFor(container, "computed_reverse")
        execDdl(
            pool,
            """CREATE TABLE order_line (
                 id INT PRIMARY KEY,
                 qty INT NOT NULL,
                 price DECIMAL(10,2) NOT NULL,
                 total AS (qty * price) PERSISTED,
                 note AS ('n' + CAST(id AS VARCHAR(10))),
                 "Menge" INT NOT NULL,
                 "Preis" DECIMAL(10,2) NOT NULL,
                 "Summe" AS ("Menge" * "Preis") PERSISTED)""",
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("persisted and non-persisted computed columns both come back with their expression") {
        val table = readSchema(pool).tables.getValue("order_line")

        val persisted = table.columns.getValue("total").generation as? ColumnGeneration.Computed
        val virtual = table.columns.getValue("note").generation as? ColumnGeneration.Computed

        withClue(table.columns.getValue("total").generation.toString()) {
            persisted.shouldNotBeNull()
            persisted.stored shouldBe true
            // Serverform (`([qty]*[price])`), aber in neutraler Schreibweise.
            persisted.expression shouldBe "qty*price"
        }
        withClue(table.columns.getValue("note").generation.toString()) {
            virtual.shouldNotBeNull()
            virtual.stored shouldBe false
            // Der Server schreibt `CAST` als `CONVERT([varchar](10),[id])` —
            // der Typname verliert seine Klammern wie jeder andere Bezeichner.
            virtual.expression shouldNotContain "["
            virtual.expression shouldContain "CONVERT"
        }
        // M9: eine nicht kleingeschriebene Spalte steht als `"Name"` da — die
        // Schreibweise, die das neutrale Modell fuer sie kennt.
        val pascal = table.columns.getValue("Summe").generation as? ColumnGeneration.Computed
        withClue(table.columns.getValue("Summe").generation.toString()) {
            pascal.shouldNotBeNull()
            pascal.expression shouldBe "\"Menge\"*\"Preis\""
        }
        // Der Wert kommt aus dem Ausdruck — ein Default daneben waere sinnlos.
        table.columns.getValue("total").default.shouldBeNull()
    }

    test("a plain column stays plain") {
        readSchema(pool).tables.getValue("order_line")
            .columns.getValue("qty").generation.shouldBeNull()
    }
})
