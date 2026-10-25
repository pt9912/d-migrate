package dev.dmigrate.driver.sqlite

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Der Schnitt der Schluesselliste aus `sqlite_master.sql`.
 *
 * Ein blosses `substringBetween('(' , ')')` genuegt dafuer nicht: SQLite
 * traegt Klammern in geschachtelten Funktionsaufrufen, in Zeichenketten und
 * in geklammerten Bezeichnern, und hinter der Liste kann eine
 * `WHERE`-Klausel stehen. Was hier falsch schneidet, landet als Ausdruck im
 * Modell und beim Generate als Anweisung, die kein Zieldialekt annimmt.
 */
class SqliteIndexKeyScannerTest : FunSpec({

    test("plain columns come back as they stand") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a, b)") shouldBe listOf("a", "b")
    }

    test("an expression keeps its own parentheses") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a, UPPER(nm))") shouldBe
            listOf("a", "UPPER(nm)")
    }

    test("nesting does not end the list early") {
        SqliteIndexKeyScanner.keysOf("CREATE UNIQUE INDEX ix ON t(COALESCE(SUBSTR(nm, 1, 3), 'x'))") shouldBe
            listOf("COALESCE(SUBSTR(nm, 1, 3), 'x')")
    }

    test("a comma inside a string literal is not a separator") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(REPLACE(nm, ',', ';'), a)") shouldBe
            listOf("REPLACE(nm, ',', ';')", "a")
    }

    test("a quoted identifier may carry commas and parentheses") {
        SqliteIndexKeyScanner.keysOf("""CREATE INDEX ix ON "odd(name," ("a,b", c)""") shouldBe
            listOf("\"a,b\"", "c")
    }

    test("the WHERE clause stays outside the key list") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(LOWER(nm)) WHERE a > 0") shouldBe
            listOf("LOWER(nm)")
    }

    test("direction and COLLATE are stripped — they are their own fields") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a DESC, LOWER(nm) COLLATE NOCASE ASC)") shouldBe
            listOf("a", "LOWER(nm)")
    }

    test("a comment between the keys is skipped") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a /* the key */, b)") shouldBe
            listOf("a /* the key */", "b")
    }

    test("an unbalanced statement yields nothing rather than a guess") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a, UPPER(nm)") shouldBe null
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t") shouldBe null
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a, )") shouldBe listOf("a")
    }

    test("one enclosing parenthesis is removed — the exact inverse of the generator's wrap") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t((lower(a)))") shouldBe listOf("lower(a)")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(lower(a))") shouldBe listOf("lower(a)")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(((lower(a))))") shouldBe listOf("(lower(a))")
    }

    test("a parenthesis that does not enclose the whole key stays") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t((a) + (b))") shouldBe listOf("(a) + (b)")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(((a) + (b)))") shouldBe listOf("(a) + (b)")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t((a + b) * 2)") shouldBe listOf("(a + b) * 2")
    }

    test("a parenthesis inside a string literal does not count") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(('(' || a))") shouldBe listOf("'(' || a")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(')' || (a))") shouldBe listOf("')' || (a)")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(((a) || ')'))") shouldBe listOf("(a) || ')'")
    }

    test("modifiers go first, then the parenthesis") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t(a, (b + 1) DESC)") shouldBe listOf("a", "b + 1")
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t((lower(a)) COLLATE NOCASE ASC)") shouldBe listOf("lower(a)")
    }

    test("a hand-written parenthesised column equals the column") {
        SqliteIndexKeyScanner.keysOf("CREATE INDEX ix ON t((a))") shouldBe listOf("a")
    }

    test("every key of a list is unwrapped on its own") {
        SqliteIndexKeyScanner.keysOf("CREATE UNIQUE INDEX ix ON t((lower(a)), b, (upper(c))) WHERE b > 0") shouldBe
            listOf("lower(a)", "b", "upper(c)")
    }
})
