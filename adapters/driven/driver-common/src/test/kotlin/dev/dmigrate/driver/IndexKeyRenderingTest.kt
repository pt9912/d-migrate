package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Ein Ausdrucks-Schluessel steht wortgleich in Klammern; endet er in einem
 * `--`-Zeilenkommentar, gehoert die schliessende Klammer auf eine neue Zeile.
 */
class IndexKeyRenderingTest : FunSpec({

    fun key(expression: String) = IndexColumn(name = expression, expression = expression).renderKey { "\"$it\"" }

    test("an expression without a comment is wrapped as before") {
        key("lower(a)") shouldBe "(lower(a))"
        key("a /* c */ + b") shouldBe "(a /* c */ + b)"
    }

    test("a column is quoted") {
        IndexColumn("a").renderKey { "\"$it\"" } shouldBe "\"a\""
    }

    test("a line comment at the end puts the closing parenthesis on its own line") {
        key("lower(a) -- c") shouldBe "(lower(a) -- c\n)"
        key("a -- x\n + b -- y") shouldBe "(a -- x\n + b -- y\n)"
    }

    test("a line comment in the middle already ends at its newline") {
        key("lower(a) -- c\n + 1") shouldBe "(lower(a) -- c\n + 1)"
    }

    test("comment characters in strings, quoted names and block comments are not a comment") {
        key("'--' || a") shouldBe "('--' || a)"
        key("\"a--b\"") shouldBe "(\"a--b\")"
        key("`a--b`") shouldBe "(`a--b`)"
        key("'it''s --' || a") shouldBe "('it''s --' || a)"
        key("a /* -- */ + b") shouldBe "(a /* -- */ + b)"
    }
})
