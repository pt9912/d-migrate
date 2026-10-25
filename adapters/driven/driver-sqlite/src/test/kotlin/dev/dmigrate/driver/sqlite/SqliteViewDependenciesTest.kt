package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SqliteViewDependenciesTest : FunSpec({

    fun schema(vararg views: Pair<String, ViewDefinition>) =
        SchemaDefinition(name = "s", version = "1", views = views.toMap())

    fun view(query: String?, deps: DependencyInfo? = null) = ViewDefinition(query = query, dependencies = deps)

    test("Bezug im Text, in jeder Anfuehrung und mit Schema-Praefix") {
        val s = schema(
            "a" to view("SELECT * FROM t"),
            "b" to view("SELECT * FROM \"T\""),
            "c" to view("SELECT * FROM `t`"),
            "d" to view("SELECT * FROM [t]"),
            "e" to view("SELECT * FROM main.t"),
            "f" to view("SELECT * FROM other"),
        )
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("a", "b", "c", "d", "e")
    }

    test("Literal und Kommentar loesen keinen Bezug aus") {
        val s = schema(
            "lit" to view("SELECT 't' AS x FROM other"),
            "cmt" to view("SELECT 1 FROM other -- t\n"),
            "blk" to view("SELECT 1 /* t */ FROM other"),
        )
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe emptySet()
    }

    test("Sicht ueber Sicht haengt transitiv an der Tabelle") {
        val s = schema(
            "v1" to view("SELECT id FROM t"),
            "v2" to view("SELECT id FROM v1"),
            "v3" to view("SELECT id FROM v2"),
            "x" to view("SELECT 1"),
        )
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("v1", "v2", "v3")
    }

    test("deklarierte dependencies zaehlen auch ohne Text, und ohne Query ist nichts abzuleiten") {
        val s = schema(
            "decl" to view(null, DependencyInfo(tables = listOf("t"))),
            "none" to view(null),
        )
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("decl")
        SqliteViewDependencies.viewNamesOf(null, "t") shouldBe emptySet()
    }

    test("viewsByTable vereint Ist und Soll") {
        val current = schema("old" to view("SELECT 1 FROM t"))
        val desired = schema("new" to view("SELECT 1 FROM t"))
        SqliteViewDependencies.viewsByTable(setOf("t"), current, desired) shouldBe mapOf("t" to setOf("old", "new"))
    }
})
