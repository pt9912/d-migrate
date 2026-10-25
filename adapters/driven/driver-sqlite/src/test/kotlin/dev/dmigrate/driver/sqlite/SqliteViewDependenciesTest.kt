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
            "g" to view("SELECT * FROM \"main\".\"t\""),
        )
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("a", "b", "c", "d", "e", "g")
        // Gross-/Kleinschreibung gilt auch fuer den Tabellennamen im Schema.
        SqliteViewDependencies.viewNamesOf(s, "T") shouldBe setOf("a", "b", "c", "d", "e", "g")
    }

    test("Ueberschaetzung ist erlaubt: ein Spaltenname oder Alias gleichen Namens zaehlt") {
        val s = schema("alias" to view("SELECT x AS t FROM other"), "fn" to view("SELECT t(1) FROM other"))
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("alias", "fn")
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

    test("dependencies.views zaehlen, Zyklen und Selbstbezug terminieren") {
        val s = schema(
            "base" to view("SELECT 1 FROM t"),
            "up" to view(null, DependencyInfo(views = listOf("base"))),
            "self" to view("SELECT * FROM self, base"),
            "loopA" to view("SELECT * FROM loopB, t"),
            "loopB" to view("SELECT * FROM loopA"),
        )
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("base", "up", "self", "loopA", "loopB")
    }

    test("ein Apostroph in einem zitierten Bezeichner verschluckt den Rest nicht") {
        val s = schema("q" to view("SELECT \"it's\" AS x FROM t"), "b" to view("SELECT [a'b] FROM t"))
        SqliteViewDependencies.viewNamesOf(s, "t") shouldBe setOf("q", "b")
    }

    test("Namen in dependencies und Text gelten ohne Beachtung der Gross-/Kleinschreibung") {
        val s = schema("decl" to view(null, DependencyInfo(tables = listOf("ORDERS"))))
        SqliteViewDependencies.viewNamesOf(s, "orders") shouldBe setOf("decl")
    }

    test("viewsByTable ohne Schema ist leer") {
        SqliteViewDependencies.viewsByTable(setOf("t"), null, null) shouldBe emptyMap()
    }

    test("viewsByTable vereint Ist und Soll") {
        val current = schema("old" to view("SELECT 1 FROM t"))
        val desired = schema("new" to view("SELECT 1 FROM t"))
        SqliteViewDependencies.viewsByTable(setOf("t"), current, desired) shouldBe mapOf("t" to setOf("old", "new"))
    }
})
