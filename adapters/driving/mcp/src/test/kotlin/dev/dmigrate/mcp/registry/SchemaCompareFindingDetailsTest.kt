package dev.dmigrate.mcp.registry

import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Die Funde nennen ihr Vorher und Nachher — auch fuer Constraints und
 * Indizes, die der Vergleich als Ganzes fuehrt.
 */
class SchemaCompareFindingDetailsTest : FunSpec({

    @Suppress("UNCHECKED_CAST")
    fun details(finding: Map<String, Any?>): Map<String, String>? = finding["details"] as Map<String, String>?

    fun single(diff: SchemaDiff, code: String) = SchemaCompareFindings.of(diff).single { it["code"] == code }

    val check = ConstraintDefinition(name = "ck_qty", type = ConstraintType.CHECK, expression = "qty > 0")

    fun tableDiff(diff: TableDiff) = SchemaDiff(tablesChanged = listOf(diff))

    test("a changed CHECK names both expressions") {
        val finding = single(
            tableDiff(TableDiff(name = "orders", constraintsChanged = listOf(ValueChange(check, check.copy(expression = "qty > 1"))))),
            "TABLE_CONSTRAINT_CHANGED",
        )
        details(finding)!!.shouldContainExactly(
            mapOf("before" to "ck_qty (check: qty > 0)", "after" to "ck_qty (check: qty > 1)"),
        )
    }

    test("a blank side still leaves both sides in the details") {
        // Die Falle im Helfer: eine blanke Seite fiele weg. Die Kurzform
        // traegt den Namen und ist deshalb nie blank.
        val finding = single(
            tableDiff(TableDiff(name = "orders", constraintsChanged = listOf(ValueChange(check.copy(expression = null), check)))),
            "TABLE_CONSTRAINT_CHANGED",
        )
        details(finding)!!.shouldContainExactly(
            mapOf("before" to "ck_qty (check)", "after" to "ck_qty (check: qty > 0)"),
        )
    }

    test("a real predicate change on an index names both predicates") {
        // Durch den echten Comparator mit Faltung: eine reine
        // Schreibweise-Differenz waere kein Fund mehr, eine echte bleibt einer.
        fun schema(where: String) = SchemaDefinition(
            name = "app", version = "1",
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer)),
                    indices = listOf(IndexDefinition(name = "ix_open", columns = listOf(IndexColumn("status")), where = where)),
                ),
            ),
        )
        val diff = SchemaComparator(canonicalizeRawExpressions = true)
            .compare(schema("status <> 'DONE'"), schema("\"status\" <> 'VOID'"))
        val finding = single(diff, "TABLE_INDEX_CHANGED")
        finding["path"] shouldBe "tables.orders.indices.ix_open"
        details(finding)!!["before"]!! shouldContain "where status <> 'DONE'"
        details(finding)!!["after"]!! shouldContain "where \"status\" <> 'VOID'"
    }

    test("added and removed indices and constraints carry their side") {
        val index = IndexDefinition(name = "ix_a", columns = listOf(IndexColumn("a")))
        val findings = SchemaCompareFindings.of(
            tableDiff(
                TableDiff(
                    name = "orders",
                    indicesAdded = listOf(index), indicesRemoved = listOf(index.copy(name = "ix_b")),
                    constraintsAdded = listOf(check), constraintsRemoved = listOf(check.copy(name = "ck_old")),
                ),
            ),
        ).associateBy { it["code"] }
        details(findings.getValue("TABLE_INDEX_ADDED")) shouldBe mapOf("after" to "ix_a [btree] on (a)")
        details(findings.getValue("TABLE_INDEX_REMOVED")) shouldBe mapOf("before" to "ix_b [btree] on (a)")
        details(findings.getValue("TABLE_CONSTRAINT_ADDED")) shouldBe mapOf("after" to "ck_qty (check: qty > 0)")
        details(findings.getValue("TABLE_CONSTRAINT_REMOVED")) shouldBe mapOf("before" to "ck_old (check: qty > 0)")
    }

    test("a changed object field carries its values, an unset side stays absent") {
        val findings = SchemaCompareFindings.of(
            SchemaDiff(
                sequencesChanged = listOf(
                    SequenceDiff(name = "s", start = ValueChange(1L, 10L), minValue = ValueChange(null, 5L)),
                ),
            ),
        )
        details(findings.single { it["path"] == "sequences.s.start" }) shouldBe mapOf("before" to "1", "after" to "10")
        details(findings.single { it["path"] == "sequences.s.min_value" }) shouldBe mapOf("after" to "5")
    }

    test("long texts and structured values stay without values") {
        val findings = SchemaCompareFindings.of(
            SchemaDiff(
                functionsChanged = listOf(
                    FunctionDiff(name = "f", body = ValueChange("BEGIN 1; END;", "BEGIN 2; END;"), definer = ValueChange("a", "b")),
                ),
            ),
        )
        findings.single { it["path"] == "functions.f.body" }.containsKey("details") shouldBe false
        details(findings.single { it["path"] == "functions.f.definer" }) shouldBe mapOf("before" to "a", "after" to "b")
    }
})
