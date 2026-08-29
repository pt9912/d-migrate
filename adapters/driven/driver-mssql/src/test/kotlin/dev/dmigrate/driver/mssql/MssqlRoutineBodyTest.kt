package dev.dmigrate.driver.mssql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Der Rumpf-Schnitt sucht das erste `AS` auf oberster Ebene. Ein `indexOf`
 * genuegt dafuer nicht — T-SQL traegt `AS` in Zeichenketten, Kommentaren,
 * geklammerten Bezeichnern und in der Parameterliste.
 */
class MssqlRoutineBodyTest : FunSpec({

    test("a procedure body starts after the top-level AS") {
        MssqlRoutineBody.extract("CREATE PROCEDURE dbo.p AS SELECT 1") shouldBe "SELECT 1"
    }

    test("an AS inside the parameter list does not count") {
        val sql = "CREATE FUNCTION f (@x INT, @y AS INT) RETURNS INT AS BEGIN RETURN 1 END"
        MssqlRoutineBody.extract(sql) shouldBe "BEGIN RETURN 1 END"
    }

    test("an AS inside a string literal does not count") {
        val sql = "CREATE PROCEDURE p AS SELECT 'value AS text'"
        MssqlRoutineBody.extract(sql) shouldBe "SELECT 'value AS text'"
    }

    test("a doubled quote inside a literal does not end it") {
        val sql = "CREATE PROCEDURE p AS SELECT 'it''s AS fine'"
        MssqlRoutineBody.extract(sql) shouldBe "SELECT 'it''s AS fine'"
    }

    test("an AS inside a bracketed identifier does not count") {
        val sql = "CREATE TRIGGER t ON [table AS name] AFTER INSERT AS SELECT 1"
        MssqlRoutineBody.extract(sql) shouldBe "SELECT 1"
    }

    test("an AS inside a line comment does not count") {
        val sql = "CREATE PROCEDURE p -- returns AS nothing\nAS SELECT 1"
        MssqlRoutineBody.extract(sql) shouldBe "SELECT 1"
    }

    test("an AS inside a block comment does not count") {
        val sql = "CREATE PROCEDURE p /* AS in here */ AS SELECT 1"
        MssqlRoutineBody.extract(sql) shouldBe "SELECT 1"
    }

    test("WITH SCHEMABINDING before the AS is no obstacle") {
        val sql = "CREATE FUNCTION f () RETURNS INT WITH SCHEMABINDING AS BEGIN RETURN 1 END"
        MssqlRoutineBody.extract(sql) shouldBe "BEGIN RETURN 1 END"
    }

    test("a word merely containing 'as' is not the keyword") {
        val sql = "CREATE PROCEDURE dbo.cascade_check AS SELECT 1"
        MssqlRoutineBody.extract(sql) shouldBe "SELECT 1"
    }

    // Lieber nichts als etwas Falsches: der Aufrufer meldet den Fall.
    test("a definition without a top-level AS yields null") {
        MssqlRoutineBody.extract("CREATE PROCEDURE p").shouldBeNull()
    }

    test("an empty body yields null rather than an empty string") {
        MssqlRoutineBody.extract("CREATE PROCEDURE p AS   ").shouldBeNull()
    }

    test("an unterminated literal does not run past the end") {
        MssqlRoutineBody.extract("CREATE PROCEDURE p AS SELECT 'unterminated").shouldBe(
            "SELECT 'unterminated",
        )
    }
})
