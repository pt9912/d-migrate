package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die **Huelle**: was der Transformer unabhaengig vom Dialekt tut.
 *
 * Er bekommt hier bewusst keine echten Dialektregeln, sondern einen Stub —
 * genau das ist der Gewinn der Aufteilung: die Orchestrierung (fremde
 * Quotierung, unbekannte Funktionen, Aliasnamen der Quelle) laesst sich
 * pruefen, ohne dass ein Dialekt im Spiel ist. Die dialekteigenen Zusicherungen
 * stehen in `<Dialekt>ViewPortabilityRulesTest` im jeweiligen Treibermodul.
 */
class ViewQueryTransformerTest : FunSpec({

    /**
     * Ein Regelsatz ohne eigene Marker und ohne Umschreibungen. Er traegt nur
     * den Dialekt — alles Weitere kommt aus der Huelle.
     */
    fun rulesFor(target: DatabaseDialect) = object : ViewPortabilityRules {
        override val dialect: DatabaseDialect = target
        override fun markers(context: ViewPortabilityContext): List<String> = emptyList()
    }

    test("Unknown function IFNULL produces W111 warning") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.MYSQL))
        val (_, notes) = transformer.transform("SELECT IFNULL(a, b) FROM t", "postgresql")
        notes shouldHaveSize 1
        notes[0].code shouldBe "W111"
        notes[0].message shouldContain "IFNULL"
    }
    test("Known functions like COUNT and SUM produce no W111 warning") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.MYSQL))
        val (_, notes) = transformer.transform("SELECT COUNT(*), SUM(amount) FROM t", "postgresql")
        notes.shouldBeEmpty()
    }
    test("Same dialect produces no W111 warning") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.MYSQL))
        val (_, notes) = transformer.transform("SELECT IFNULL(a, b) FROM t", "mysql")
        notes.shouldBeEmpty()
    }

    // ── Portability assessment (I-09) ───────────
    test("assessPortability: backticks make a body non-portable to PostgreSQL") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        val verdict = transformer.assessPortability("SELECT `x` FROM `t`", "mysql")
        verdict.portable shouldBe false
        verdict.reason!! shouldContain "backtick"
    }
    test("assessPortability: cross-dialect unknown function is non-portable") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        val verdict = transformer.assessPortability("SELECT group_concat(name) FROM t", "mysql")
        verdict.portable shouldBe false
        verdict.reason!! shouldContain "GROUP_CONCAT"
    }
    test("assessPortability: plain cross-dialect SELECT is portable") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        transformer.assessPortability("SELECT id, name FROM users", "mysql").portable shouldBe true
    }
    test("assessPortability: same-dialect body is portable even with unknown functions") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        transformer.assessPortability("SELECT custom_fn(x) FROM t", "postgresql").portable shouldBe true
    }
    test("assessPortability: dialect alias 'postgres' is not treated as cross-dialect (M1)") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        // Were 'postgres' judged foreign, custom_fn would be flagged; the alias must resolve to PG.
        transformer.assessPortability("SELECT custom_fn(x) FROM t", "postgres").portable shouldBe true
    }
    test("assessPortability: a backtick inside a string literal does not trip the PG check (M2)") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        transformer.assessPortability("SELECT 'a`b' AS x FROM t", "postgresql").portable shouldBe true
    }
    test("assessPortability: :: inside a string literal does not trip the MySQL check (N4)") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.MYSQL))
        transformer.assessPortability("SELECT 'a::b' AS x FROM t", "mysql").portable shouldBe true
    }
    test("assessPortability: T-SQL bracket quoting from an mssql source is non-portable to PostgreSQL") {
        val transformer = ViewQueryTransformer(rulesFor(DatabaseDialect.POSTGRESQL))
        transformer.assessPortability("SELECT [id] FROM [dbo].[users]", "mssql").let {
            it.portable shouldBe false
            it.reason shouldContain "bracket"
        }
        // PG array subscripts from a PG source are not brackets-as-quoting.
        transformer.assessPortability("SELECT tags[1] FROM t", "postgresql").portable shouldBe true
        transformer.assessPortability("SELECT '[x]' AS s FROM t", "mssql").portable shouldBe true
    }
})
