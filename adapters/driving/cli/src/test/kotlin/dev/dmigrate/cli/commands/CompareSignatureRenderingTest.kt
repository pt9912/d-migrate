package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die CLI rendert fuer einen geaenderten CHECK oder ein geaendertes
 * Index-Praedikat ein **gefuelltes** Vorher/Nachher. Bis dahin standen beide
 * Seiten als `ck_x (check) -> ck_x (check)` da — die Zeile sagte nicht, was
 * sich geaendert hat.
 */
class CompareSignatureRenderingTest : FunSpec({

    fun schema(check: String?, where: String) = SchemaDefinition(
        name = "shop", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer),
                    "qty" to ColumnDefinition(NeutralType.Integer),
                ),
                primaryKey = listOf("id"),
                indices = listOf(IndexDefinition(name = "ix_open", columns = listOf(IndexColumn("qty")), where = where)),
                constraints = listOf(ConstraintDefinition(name = "ck_qty", type = ConstraintType.CHECK, expression = check)),
            ),
        ),
    )

    val bundle = DefaultSchemaCompareWiringFactory.build(CliContext(quiet = true))

    fun document(source: SchemaDefinition, target: SchemaDefinition): SchemaCompareDocument {
        val diff = bundle.comparator(CompareSide(source), CompareSide(target))
        return SchemaCompareDocument(
            status = "different", exitCode = 1, source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1), diff = bundle.projectDiff(diff),
        )
    }

    test("a changed CHECK and a changed index predicate name both sides in plain and json") {
        val doc = document(schema("qty > 0", "qty > 0"), schema("qty > 1", "qty > 5"))
        val table = doc.diff!!.tablesChanged.single()

        table.constraintsChanged.single() shouldBe
            StringChange("ck_qty (check: qty > 0)", "ck_qty (check: qty > 1)")
        table.indicesChanged.single() shouldBe
            StringChange("ix_open [btree] on (qty) where qty > 0", "ix_open [btree] on (qty) where qty > 5")

        val plain = bundle.renderPlain(doc)
        plain shouldContain "~ constraint ck_qty (check: qty > 0) -> ck_qty (check: qty > 1)"
        plain shouldContain "~ index ix_open [btree] on (qty) where qty > 0 -> ix_open [btree] on (qty) where qty > 5"
        bundle.renderJson(doc) shouldContain "ck_qty (check: qty > 1)"
    }

    test("a blank side still renders the other side and the name") {
        val doc = document(schema(null, "qty > 0"), schema("qty > 1", "qty > 0"))
        doc.diff!!.tablesChanged.single().constraintsChanged.single() shouldBe
            StringChange("ck_qty (check)", "ck_qty (check: qty > 1)")
    }
})
