package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Der Ausdruck steht nur im abgelegten `CREATE TABLE`-Text — und der darf
 * Klammern, Zeichenketten und zitierte Bezeichner enthalten. Deshalb wird hier
 * geprueft, was ein Regex reihenweise falsch machen wuerde.
 */
class SqliteGeneratedColumnScannerTest : FunSpec({

    test("stored and virtual columns both yield their expression") {
        val sql = """CREATE TABLE order_line (
              id INTEGER PRIMARY KEY,
              quantity INTEGER NOT NULL,
              unit_price NUMERIC NOT NULL,
              line_total NUMERIC GENERATED ALWAYS AS (quantity * unit_price) STORED,
              line_note TEXT GENERATED ALWAYS AS ('n' || id) VIRTUAL
            )"""

        SqliteGeneratedColumnScanner.expressionsOf(sql) shouldBe mapOf(
            "line_total" to "quantity * unit_price",
            "line_note" to "'n' || id",
        )
    }

    test("SQLite allows the short form without GENERATED ALWAYS") {
        val sql = """CREATE TABLE t (a INT, b INT AS (a * 2) STORED)"""

        SqliteGeneratedColumnScanner.expressionsOf(sql) shouldBe mapOf("b" to "a * 2")
    }

    test("nested parentheses in the expression survive") {
        val sql = """CREATE TABLE t (a INT, b INT AS (((a + 1) * (a - 1))) VIRTUAL)"""

        SqliteGeneratedColumnScanner.expressionsOf(sql) shouldBe mapOf("b" to "((a + 1) * (a - 1))")
    }

    test("a comma inside a string literal does not split the column list") {
        val sql = """CREATE TABLE t (a TEXT, b TEXT AS (replace(a, ',', ';')) VIRTUAL)"""

        SqliteGeneratedColumnScanner.expressionsOf(sql) shouldBe mapOf("b" to "replace(a, ',', ';')")
    }

    test("quoted column names come back unquoted") {
        val sql = """CREATE TABLE t ("odd name" INT, "b c" INT AS ("odd name" * 2) STORED)"""

        SqliteGeneratedColumnScanner.expressionsOf(sql) shouldBe mapOf("b c" to "\"odd name\" * 2")
    }

    test("table constraints are not mistaken for columns") {
        val sql = """CREATE TABLE t (
              a INT, b INT,
              CONSTRAINT pk PRIMARY KEY (a),
              CHECK (b > 0),
              FOREIGN KEY (a) REFERENCES other(id)
            )"""

        SqliteGeneratedColumnScanner.expressionsOf(sql).shouldBeEmpty()
    }

    test("a plain table yields nothing") {
        SqliteGeneratedColumnScanner.expressionsOf("CREATE TABLE t (a INT, b TEXT)").shouldBeEmpty()
    }
})
