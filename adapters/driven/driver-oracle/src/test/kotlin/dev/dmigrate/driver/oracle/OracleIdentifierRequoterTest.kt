package dev.dmigrate.driver.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Was der Requoter anfasst — und vor allem, was nicht.
 *
 * Ein falsch gesetztes Anfuehrungszeichen waere schlimmer als ein fehlendes:
 * es macht aus gueltigem rohem SQL ungueltiges. Diese Spezifikation haelt die
 * Grenzen fest, an denen er die Finger laesst.
 */
class OracleIdentifierRequoterTest : FunSpec({

    val known = mapOf(
        "orders" to "orders",
        "total_amount" to "total_amount",
        "status" to "status",
        "level" to "level",
    )

    fun requote(sql: String) = OracleIdentifierRequoter.requote(sql, known) { "\"$it\"" }

    test("a known identifier is quoted") {
        requote("total_amount >= 0") shouldBe "\"total_amount\" >= 0"
    }

    test("an unknown word stays as it is") {
        // Ein Alias, eine Funktion aus einem Paket, eine Spalte aus einer
        // Tabelle, die das Schema nicht fuehrt — d-migrate raet dort nicht.
        requote("o.whatever > 0") shouldBe "o.whatever > 0"
    }

    test("a qualified reference quotes only the part the schema knows") {
        requote("o.status = 'x'") shouldBe "o.\"status\" = 'x'"
    }

    test("a string literal is untouched, even when it reads like a column") {
        requote("status != 'status'") shouldBe "\"status\" != 'status'"
        requote("'total_amount'") shouldBe "'total_amount'"
    }

    test("an already quoted identifier is left alone") {
        requote("\"total_amount\" >= 0") shouldBe "\"total_amount\" >= 0"
    }

    test("a word followed by a parenthesis is a function call") {
        // `COUNT(*)` ist keine Spalte -- und eine Spalte namens `status`
        // macht `status(1)` nicht zu einer.
        requote("SELECT COUNT(*) FROM orders") shouldBe "SELECT COUNT(*) FROM \"orders\""
        requote("status (1)") shouldBe "status (1)"
    }

    test("a reserved word keeps its role, even when a column shares its name") {
        // `level` steht im Schema als Spalte UND ist ein Oracle-Schluesselwort.
        // An dieser Stelle im Satz ist es das Schluesselwort.
        requote("SELECT level FROM orders") shouldBe "SELECT level FROM \"orders\""
    }

    test("comments are skipped") {
        requote("-- total_amount\nstatus = 1") shouldBe "-- total_amount\n\"status\" = 1"
        requote("/* orders */ status") shouldBe "/* orders */ \"status\""
    }

    test("a longer word that merely starts with a known one is not a match") {
        requote("statuses > 0") shouldBe "statuses > 0"
        requote("my_status > 0") shouldBe "my_status > 0"
    }

    test("without known identifiers nothing happens at all") {
        OracleIdentifierRequoter.requote("status = 1", emptyMap()) { "\"$it\"" } shouldBe "status = 1"
    }

    test("the schema's own spelling wins, not the one in the text") {
        val mixed = mapOf("orderdate" to "OrderDate")
        OracleIdentifierRequoter.requote("orderdate > 0", mixed) { "\"$it\"" } shouldBe "\"OrderDate\" > 0"
    }
})
