package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ViewQueryTransformerTest : FunSpec({

    // ── MySQL transformations ────────────────────

    test("MySQL: DATE_TRUNC month transforms to DATE_FORMAT") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('month', created_at) FROM t", "postgresql")
        result shouldBe "SELECT DATE_FORMAT(created_at, '%Y-%m-01') FROM t"
    }

    test("MySQL: DATE_TRUNC year transforms to DATE_FORMAT") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('year', created_at) FROM t", "postgresql")
        result shouldBe "SELECT DATE_FORMAT(created_at, '%Y-01-01') FROM t"
    }

    test("MySQL: unsupported DATE_TRUNC unit falls back to original function call") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC('quarter', created_at) FROM t", "postgresql")
        result.replace(Regex("\\s+"), " ") shouldBe "SELECT DATE_TRUNC('quarter', created_at) FROM t"
    }

    test("MySQL: malformed DATE_TRUNC call stays as empty function call") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT DATE_TRUNC() FROM t", "postgresql")
        result shouldBe "SELECT DATE_TRUNC() FROM t"
    }

    test("MySQL: EXTRACT YEAR FROM transforms to YEAR()") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT EXTRACT(YEAR FROM created_at) FROM t", "postgresql")
        result shouldBe "SELECT YEAR(created_at) FROM t"
    }

    test("MySQL: LENGTH transforms to CHAR_LENGTH") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT LENGTH(name) FROM t", "postgresql")
        result shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }

    test("MySQL: CHAR_LENGTH is not corrupted to CHAR_CHAR_LENGTH") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT CHAR_LENGTH(name) FROM t", "postgresql")
        result shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }

    test("MySQL: TRUE and FALSE transform to 1 and 0") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT * FROM t WHERE active = TRUE AND deleted = FALSE", "postgresql")
        result shouldBe "SELECT * FROM t WHERE active = 1 AND deleted = 0"
    }

    test("MySQL: CURRENT_DATE transforms to CURDATE()") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT CURRENT_DATE FROM t", "postgresql")
        result shouldBe "SELECT CURDATE() FROM t"
    }

    test("MySQL: CURRENT_TIME transforms to CURTIME()") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (result, _) = transformer.transform("SELECT CURRENT_TIME FROM t", "postgresql")
        result shouldBe "SELECT CURTIME() FROM t"
    }

    // ── SQLite transformations ───────────────────

    test("SQLite: NOW() transforms to datetime('now')") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT NOW() FROM t", "postgresql")
        result shouldBe "SELECT datetime('now') FROM t"
    }

    test("SQLite: CURRENT_TIMESTAMP transforms to datetime('now')") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT CURRENT_TIMESTAMP FROM t", "postgresql")
        result shouldBe "SELECT datetime('now') FROM t"
    }

    test("SQLite: CONCAT transforms to ||") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT CONCAT(first, last) FROM t", "postgresql")
        result shouldBe "SELECT first || last FROM t"
    }

    test("SQLite: SUBSTRING FROM FOR transforms to SUBSTR") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT SUBSTRING(name FROM 1 FOR 3) FROM t", "postgresql")
        result shouldBe "SELECT SUBSTR(name, 1, 3) FROM t"
    }

    test("SQLite: EXTRACT MONTH FROM transforms to CAST(strftime('%m', ...) AS INTEGER)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT EXTRACT(MONTH FROM created_at) FROM t", "postgresql")
        result shouldBe "SELECT CAST(strftime('%m', created_at) AS INTEGER) FROM t"
    }

    test("SQLite: TRUE transforms to 1") {
        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (result, _) = transformer.transform("SELECT * FROM t WHERE active = TRUE", "postgresql")
        result shouldBe "SELECT * FROM t WHERE active = 1"
    }

    // ── PostgreSQL transformations ───────────────

    test("PostgreSQL: NOW() transforms to CURRENT_TIMESTAMP") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        val (result, _) = transformer.transform("SELECT NOW() FROM t", null)
        result shouldBe "SELECT CURRENT_TIMESTAMP FROM t"
    }

    // ── Unknown function detection ──────────────

    test("Unknown function IFNULL produces W111 warning") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (_, notes) = transformer.transform("SELECT IFNULL(a, b) FROM t", "postgresql")
        notes shouldHaveSize 1
        notes[0].code shouldBe "W111"
        notes[0].message shouldContain "IFNULL"
    }

    test("Known functions like COUNT and SUM produce no W111 warning") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (_, notes) = transformer.transform("SELECT COUNT(*), SUM(amount) FROM t", "postgresql")
        notes.shouldBeEmpty()
    }

    test("Same dialect produces no W111 warning") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (_, notes) = transformer.transform("SELECT IFNULL(a, b) FROM t", "mysql")
        notes.shouldBeEmpty()
    }

    // ── Portability assessment (I-09) ───────────

    test("assessPortability: backticks make a body non-portable to PostgreSQL") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        val verdict = transformer.assessPortability("SELECT `x` FROM `t`", "mysql")
        verdict.portable shouldBe false
        verdict.reason!! shouldContain "backtick"
    }

    test("assessPortability: cross-dialect unknown function is non-portable") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        val verdict = transformer.assessPortability("SELECT group_concat(name) FROM t", "mysql")
        verdict.portable shouldBe false
        verdict.reason!! shouldContain "GROUP_CONCAT"
    }

    test("assessPortability: plain cross-dialect SELECT is portable") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        transformer.assessPortability("SELECT id, name FROM users", "mysql").portable shouldBe true
    }

    test("assessPortability: same-dialect body is portable even with unknown functions") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        transformer.assessPortability("SELECT custom_fn(x) FROM t", "postgresql").portable shouldBe true
    }

    test("assessPortability: backticks are fine for a MySQL target") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        transformer.assessPortability("SELECT `x` FROM `t`", null).portable shouldBe true
    }

    test("assessPortability: dialect alias 'postgres' is not treated as cross-dialect (M1)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        // Were 'postgres' judged foreign, custom_fn would be flagged; the alias must resolve to PG.
        transformer.assessPortability("SELECT custom_fn(x) FROM t", "postgres").portable shouldBe true
    }

    test("assessPortability: a backtick inside a string literal does not trip the PG check (M2)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        transformer.assessPortability("SELECT 'a`b' AS x FROM t", "postgresql").portable shouldBe true
    }

    test("assessPortability: MySQL+PG-portable FLOOR is not flagged cross-dialect (M3)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        transformer.assessPortability("SELECT FLOOR(x) FROM t", "mysql").portable shouldBe true
    }

    test("assessPortability: PG :: cast is non-portable to MySQL (N4)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val verdict = transformer.assessPortability("SELECT (x)::text FROM t", "postgresql")
        verdict.portable shouldBe false
        verdict.reason!! shouldContain "::"
    }

    test("assessPortability: PG || concat is non-portable to MySQL (N4)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        transformer.assessPortability("SELECT a || b FROM t", "postgresql").portable shouldBe false
    }

    test("assessPortability: same-dialect MySQL || (logical OR) stays portable (N4)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        transformer.assessPortability("SELECT a FROM t WHERE x || y", "mysql").portable shouldBe true
    }

    test("assessPortability: :: inside a string literal does not trip the MySQL check (N4)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        transformer.assessPortability("SELECT 'a::b' AS x FROM t", "mysql").portable shouldBe true
    }

    test("assessPortability: `::`, `||` and LIMIT are non-portable to MSSQL regardless of source dialect") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
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
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
        transformer.assessPortability("SELECT date_trunc('month', created_at) FROM t", "postgresql").portable shouldBe false
        transformer.assessPortability("SELECT now() FROM t", "postgresql").portable shouldBe false
        transformer.assessPortability("SELECT strftime('%Y', d) FROM t", "sqlite").portable shouldBe false
        transformer.assessPortability("SELECT COUNT(*), COALESCE(a, b), LEN(name) FROM t", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT ROW_NUMBER() OVER (ORDER BY id) FROM t", "postgresql").portable shouldBe true
    }

    test("assessPortability: LIMIT as column name or alias is fine for MSSQL, LIMIT clause is not") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
        transformer.assessPortability("SELECT quota AS limit FROM plans", "mssql").portable shouldBe true
        transformer.assessPortability("SELECT p.limit FROM plans p", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT id FROM t ORDER BY id LIMIT 5", "postgresql").portable shouldBe false
    }

    test("assessPortability: T-SQL bracket quoting from an mssql source is non-portable to PostgreSQL") {
        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        transformer.assessPortability("SELECT [id] FROM [dbo].[users]", "mssql").let {
            it.portable shouldBe false
            it.reason shouldContain "bracket"
        }
        // PG array subscripts from a PG source are not brackets-as-quoting.
        transformer.assessPortability("SELECT tags[1] FROM t", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT '[x]' AS s FROM t", "mssql").portable shouldBe true
    }

    test("assessPortability: a bare top-level ORDER BY is non-portable to MSSQL (Msg 1033)") {
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
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
        val transformer = ViewQueryTransformer(DatabaseDialect.MSSQL)
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
