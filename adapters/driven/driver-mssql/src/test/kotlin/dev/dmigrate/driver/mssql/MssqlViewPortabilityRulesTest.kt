package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.ViewQueryTransformer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Was SQL Server an einem fremden View-Rumpf nicht versteht.
 *
 * T-SQL hat kein Umschreibregelwerk: was es nicht kennt, muss als nicht
 * portabel auffallen, sonst landet der Rumpf woertlich in
 * `CREATE OR ALTER VIEW`.
 */
class MssqlViewPortabilityRulesTest : FunSpec({

    test("assessPortability: `::`, `||` and LIMIT are non-portable to MSSQL regardless of source dialect") {
        val transformer = ViewQueryTransformer(MssqlViewPortabilityRules)
        transformer.assessPortability("SELECT id::text FROM t", "postgresql").let {
            it.portable shouldBe false
            it.reason shouldContain "::"
        }
        transformer.assessPortability("SELECT a || b FROM t", null).portable shouldBe false
        transformer.assessPortability("SELECT id FROM t LIMIT 10", "sqlite").let {
            it.portable shouldBe false
            it.reason shouldContain "LIMIT"
        }
        // Inside string literals the markers are ignored.
        transformer.assessPortability("SELECT 'a||b::c limit' AS s FROM t", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT id, name FROM users WHERE id > 0", "postgresql").portable shouldBe true
    }
    test("assessPortability: PG/MySQL/SQLite-only functions are non-portable to MSSQL, T-SQL functions are known") {
        val transformer = ViewQueryTransformer(MssqlViewPortabilityRules)
        transformer.assessPortability("SELECT date_trunc('month', created_at) FROM t", "postgresql").portable shouldBe false
        transformer.assessPortability("SELECT now() FROM t", "postgresql").portable shouldBe false
        transformer.assessPortability("SELECT strftime('%Y', d) FROM t", "sqlite").portable shouldBe false
        transformer.assessPortability("SELECT COUNT(*), COALESCE(a, b), LEN(name) FROM t", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT ROW_NUMBER() OVER (ORDER BY id) FROM t", "postgresql").portable shouldBe true
    }
    test("assessPortability: LIMIT as column name or alias is fine for MSSQL, LIMIT clause is not") {
        val transformer = ViewQueryTransformer(MssqlViewPortabilityRules)
        transformer.assessPortability("SELECT quota AS limit FROM plans", "mssql").portable shouldBe true
        transformer.assessPortability("SELECT p.limit FROM plans p", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT id FROM t ORDER BY id LIMIT 5", "postgresql").portable shouldBe false
    }
    test("assessPortability: a bare top-level ORDER BY is non-portable to MSSQL (Msg 1033)") {
        val transformer = ViewQueryTransformer(MssqlViewPortabilityRules)
        // PostgreSQL erlaubt ORDER BY im View-Body, SQL Server nicht.
        transformer.assessPortability("SELECT a, b FROM t ORDER BY b DESC", "postgresql").let {
            it.portable shouldBe false
            it.reason shouldContain "ORDER BY"
        }
        // Mit TOP/OFFSET ist es gueltiges T-SQL.
        transformer.assessPortability("SELECT TOP 10 a FROM t ORDER BY a", "mssql").portable shouldBe true
        transformer.assessPortability(
            "SELECT a FROM t ORDER BY a OFFSET 0 ROWS FETCH NEXT 5 ROWS ONLY", "mssql",
        ).portable shouldBe true
        // Fensterfunktionen und Unterabfragen tragen ihr ORDER BY in Klammern.
        transformer.assessPortability(
            "SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, id FROM t", "mssql",
        ).portable shouldBe true
        transformer.assessPortability("SELECT a FROM t", "postgresql").portable shouldBe true
    }
    test("assessPortability: only a real T-SQL limiter clause lifts the Msg-1033 verdict") {
        val transformer = ViewQueryTransformer(MssqlViewPortabilityRules)
        // PostgreSQLs `OFFSET n` ohne `ROWS` ist kein T-SQL-Limiter — der Body
        // bliebe ungueltiges T-SQL und muss weiterhin als nicht portabel gelten.
        transformer.assessPortability("SELECT a FROM t ORDER BY a OFFSET 10", "postgresql").let {
            it.portable shouldBe false
            it.reason shouldContain "ORDER BY"
        }
        transformer.assessPortability("SELECT a FROM t ORDER BY a OFFSET 10 ROWS", "mssql")
            .portable shouldBe true
        // Wortgleiche Bezeichner sind keine Klauseln.
        transformer.assessPortability("SELECT t.top, t.fetch FROM t ORDER BY t.top", "mssql")
            .portable shouldBe false
        transformer.assessPortability("SELECT a AS top FROM t ORDER BY a", "mssql")
            .portable shouldBe false
        // TOP (n) ist die geklammerte T-SQL-Form.
        transformer.assessPortability("SELECT TOP (10) a FROM t ORDER BY a", "mssql")
            .portable shouldBe true
        // FOR XML / FOR JSON erlauben ORDER BY ebenfalls (Msg 1033 nennt sie).
        transformer.assessPortability("SELECT a FROM t ORDER BY a FOR XML PATH('')", "mssql")
            .portable shouldBe true
        transformer.assessPortability("SELECT a FROM t ORDER BY a FOR JSON PATH", "mssql")
            .portable shouldBe true
    }
})
