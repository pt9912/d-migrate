package dev.dmigrate.driver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Welche rohen Ausdruecke ein Zieldialekt ueberhaupt parsen kann.
 *
 * Die Marker sind bewusst nur **harte** Fehler: was ohne Herkunft nicht sicher
 * zu entscheiden ist, bleibt draussen. Ein falsches „nicht portabel" verwirft
 * einen gueltigen Constraint benannt; ein falsches „portabel" erzeugt DDL, die
 * erst der Zielserver ablehnt. Die Asymmetrie faellt zugunsten des Verwerfens
 * aus, aber nur, wo die Aussage sicher ist.
 */
class RawSqlExpressionPortabilityTest : FunSpec({

    val allDialects = DatabaseDialect.entries

    fun assess(expr: String?, target: DatabaseDialect) = RawSqlExpressionPortability.assess(expr, target)

    // ── PostgreSQL-Eigenheiten ───────────────────────────────────

    test("a PostgreSQL cast is fine at home and a hard error everywhere else") {
        val expr = "(amt > (0)::numeric)"
        assess(expr, DatabaseDialect.POSTGRESQL).portable shouldBe true
        for (target in allDialects - DatabaseDialect.POSTGRESQL) {
            withClue("$target") {
                val verdict = assess(expr, target)
                verdict.portable shouldBe false
                verdict.reason!! shouldContain "::"
            }
        }
    }

    test("PostgreSQL's internal LIKE operator is fine at home and a hard error everywhere else") {
        // Der Reverse liefert `~~` statt `LIKE` — es ist der Operator, den
        // PostgreSQL intern fuehrt, und kein anderer Dialekt kennt ihn.
        val expr = "(email ~~ '%@%')"
        assess(expr, DatabaseDialect.POSTGRESQL).portable shouldBe true
        for (target in allDialects - DatabaseDialect.POSTGRESQL) {
            withClue("$target") { assess(expr, target).portable shouldBe false }
        }
    }

    // ── MySQL-Eigenheiten ────────────────────────────────────────

    test("backtick quoting is fine on MySQL and a hard error everywhere else") {
        val expr = "(`amt` > 0)"
        assess(expr, DatabaseDialect.MYSQL).portable shouldBe true
        for (target in allDialects - DatabaseDialect.MYSQL) {
            withClue("$target") {
                assess(expr, target).reason!! shouldContain "backtick"
            }
        }
    }

    // ── T-SQL kennt `||` ueberhaupt nicht ────────────────────────

    test("concatenation with || is refused for SQL Server only") {
        val expr = "(a || b <> '')"
        assess(expr, DatabaseDialect.MSSQL).portable shouldBe false
        // Auf MySQL ist `||` gueltig -- als logisches ODER. Der Ausdruck kippt
        // dort still von Verkettung auf ODER, aber ob der Autor nicht genau das
        // meinte, sagt nur die Herkunft, und die traegt ein CHECK nicht.
        assess(expr, DatabaseDialect.MYSQL).portable shouldBe true
        assess(expr, DatabaseDialect.POSTGRESQL).portable shouldBe true
        assess(expr, DatabaseDialect.SQLITE).portable shouldBe true
        assess(expr, DatabaseDialect.ORACLE).portable shouldBe true
    }

    // ── Was NICHT anschlagen darf ────────────────────────────────

    test("a marker inside a string literal is text, not syntax") {
        // Sonst verwuerfe die Pruefung einen voellig gueltigen Constraint.
        for (target in allDialects) {
            withClue("$target") {
                assess("(note <> 'a::b')", target).portable shouldBe true
                assess("(note <> 'x ~~ y')", target).portable shouldBe true
            }
        }
    }

    test("a plain expression is portable everywhere") {
        for (target in allDialects) {
            withClue("$target") {
                assess("(quantity * unit_price > 0)", target).portable shouldBe true
                assess("(status IN ('NEW', 'DONE'))", target).portable shouldBe true
            }
        }
    }

    test("no expression is not a finding") {
        for (target in allDialects) {
            assess(null, target).portable shouldBe true
            assess("   ", target).portable shouldBe true
        }
    }

    test("T-SQL bracket quoting is deliberately NOT flagged") {
        // `[` steht ebenso in JSON-Pfaden und Array-Ausdruecken; ohne Herkunft
        // waere die Unterscheidung geraten, und ein falsches „nicht portabel"
        // verwuerfe einen gueltigen Constraint.
        for (target in allDialects) {
            withClue("$target") { assess("([a] > 0)", target).portable shouldBe true }
        }
    }

    // ── Die Meldung ──────────────────────────────────────────────

    test("the note names the field, the target and the reason") {
        val verdict = assess("(amt > (0)::numeric)", DatabaseDialect.MSSQL)
        val note = RawSqlExpressionPortability.notPortableNote(
            "constraint", "ck_amt", "CHECK expression", verdict.reason, DatabaseDialect.MSSQL,
        )
        note.code shouldBe "E053"
        note.objectName shouldBe "ck_amt"
        note.message shouldContain "CHECK expression"
        note.message shouldContain "mssql"
        note.message shouldContain "::"
    }
})
