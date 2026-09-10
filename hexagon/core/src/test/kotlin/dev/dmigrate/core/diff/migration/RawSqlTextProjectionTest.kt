package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Die vier Felder, die rohen SQL-Text tragen — und nur die.
 *
 * Zu wenig auszublenden liesse den Post-Compare weiter bei jedem Lauf Drift
 * melden; zu viel auszublenden liesse eine echte Aenderung durchgehen.
 */
class RawSqlTextProjectionTest : FunSpec({

    fun schemaWith(
        views: Map<String, ViewDefinition> = emptyMap(),
        indices: List<IndexDefinition> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
    ) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf("a" to ColumnDefinition(NeutralType.Integer)),
                indices = indices,
                constraints = constraints,
            ),
        ),
        views = views,
    )

    test("zwei Schreibweisen desselben CHECKs fallen auf dieselbe Form") {
        val authored = schemaWith(
            constraints = listOf(ConstraintDefinition(name = "c", type = ConstraintType.CHECK, expression = "a >= 18")),
        )
        val catalog = schemaWith(
            constraints = listOf(ConstraintDefinition(name = "c", type = ConstraintType.CHECK, expression = "((a >= 18))")),
        )

        RawSqlTextProjection.blank(authored) shouldBe RawSqlTextProjection.blank(catalog)
    }

    test("zwei Schreibweisen desselben Sichten-Rumpfs ebenso") {
        val authored = schemaWith(views = mapOf("v" to ViewDefinition(query = "SELECT a FROM t")))
        val catalog = schemaWith(views = mapOf("v" to ViewDefinition(query = " SELECT\n  a\n FROM t;")))

        RawSqlTextProjection.blank(authored) shouldBe RawSqlTextProjection.blank(catalog)
    }

    test("Index-Praedikat und Ausdrucks-Schluessel ebenso — der Ausdruck steht auch im Etikett") {
        val authored = schemaWith(
            indices = listOf(
                IndexDefinition(
                    name = "i", where = "a > 0",
                    columns = listOf(IndexColumn(name = "upper(nm)", expression = "upper(nm)")),
                ),
            ),
        )
        val catalog = schemaWith(
            indices = listOf(
                IndexDefinition(
                    name = "i", where = "(a > 0)",
                    columns = listOf(IndexColumn(name = "upper(nm::text)", expression = "upper(nm::text)")),
                ),
            ),
        )

        RawSqlTextProjection.blank(authored) shouldBe RawSqlTextProjection.blank(catalog)
    }

    test("gesetzt bleibt von nicht gesetzt unterscheidbar") {
        val withCheck = schemaWith(
            constraints = listOf(ConstraintDefinition(name = "c", type = ConstraintType.CHECK, expression = "a >= 18")),
        )
        val without = schemaWith(
            constraints = listOf(ConstraintDefinition(name = "c", type = ConstraintType.CHECK, expression = null)),
        )

        RawSqlTextProjection.blank(withCheck) shouldNotBe RawSqlTextProjection.blank(without)
    }

    test("was kein roher SQL-Text ist, bleibt stehen") {
        val key = listOf(IndexColumn(name = "a"))
        val a = schemaWith(indices = listOf(IndexDefinition(name = "i", columns = key, unique = false)))
        val b = schemaWith(indices = listOf(IndexDefinition(name = "i", columns = key, unique = true)))

        RawSqlTextProjection.blank(a) shouldNotBe RawSqlTextProjection.blank(b)
    }

    test("eine gewoehnliche Spalte im Index bleibt unangetastet") {
        val schema = schemaWith(
            indices = listOf(IndexDefinition(name = "i", columns = listOf(IndexColumn(name = "a")))),
        )

        RawSqlTextProjection.blank(schema).tables["t"]!!.indices.single().columns.single().name shouldBe "a"
    }
})
