package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Die Serverversion entscheidet ueber eine Faehigkeit, nicht ueber eine
 * Anzeige — deshalb wird sie hier auf die Faelle geprueft, die der Leser
 * wirklich vorfindet.
 */
class PostgresServerVersionTest : FunSpec({

    test("the plain pair that PostgreSQL reports") {
        PostgresServerVersion.parse("18.6") shouldBe PostgresServerVersion(18, 6)
        PostgresServerVersion.parse("16.4") shouldBe PostgresServerVersion(16, 4)
    }

    test("a distribution suffix is not part of the version") {
        PostgresServerVersion.parse("18.6 (Debian 18.6-1.pgdg13+2)") shouldBe PostgresServerVersion(18, 6)
    }

    test("what carries no leading pair yields nothing") {
        PostgresServerVersion.parse("unknown").shouldBeNull()
        PostgresServerVersion.parse("").shouldBeNull()
        PostgresServerVersion.parse("18").shouldBeNull()
    }

    test("SET EXPRESSION starts at 17") {
        PostgresServerVersion(16, 9).supportsSetExpression shouldBe false
        PostgresServerVersion(17, 0).supportsSetExpression shouldBe true
        PostgresServerVersion(18, 6).supportsSetExpression shouldBe true
        // Die zugesagte Untergrenze kann es nicht — genau deshalb gibt es den Typ.
        PostgresServerVersion(14, 0).supportsSetExpression shouldBe false
    }

    test("ordering follows major before minor") {
        (PostgresServerVersion(17, 0) > PostgresServerVersion(16, 9)) shouldBe true
        (PostgresServerVersion(18, 2) > PostgresServerVersion(18, 1)) shouldBe true
        PostgresServerVersion(18, 6).compareTo(PostgresServerVersion(18, 6)) shouldBe 0
    }
})
