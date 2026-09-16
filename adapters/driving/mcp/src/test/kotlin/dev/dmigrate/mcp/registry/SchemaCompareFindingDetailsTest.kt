package dev.dmigrate.mcp.registry

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.TriggerDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
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

    context("Die Werte stehen in der Schreibweise des Schema-Dokuments") {

        test("enum values are lower case, lists are bracketed, trigger events in document order") {
            val findings = SchemaCompareFindings.of(
                SchemaDiff(
                    triggersChanged = listOf(
                        TriggerDiff(
                            name = "tr",
                            event = ValueChange(
                                setOf(TriggerEvent.INSERT),
                                linkedSetOf(TriggerEvent.UPDATE, TriggerEvent.INSERT),
                            ),
                            timing = ValueChange(TriggerTiming.AFTER, TriggerTiming.INSTEAD_OF),
                            forEach = ValueChange(TriggerForEach.ROW, TriggerForEach.STATEMENT),
                        ),
                    ),
                    functionsChanged = listOf(
                        FunctionDiff(
                            name = "f",
                            security = ValueChange(null, RoutineSecurity.DEFINER),
                            searchPath = ValueChange(listOf("public"), listOf("app", "public")),
                        ),
                    ),
                    customTypesChanged = listOf(
                        CustomTypeDiff(
                            name = "t",
                            kind = ValueChange(CustomTypeKind.ENUM, CustomTypeKind.DOMAIN),
                            values = ValueChange(listOf("a"), listOf("a", "b")),
                        ),
                    ),
                ),
            )
            fun at(path: String) = details(findings.single { it["path"] == path })
            at("triggers.tr.event") shouldBe mapOf("before" to "[insert]", "after" to "[insert, update]")
            at("triggers.tr.timing") shouldBe mapOf("before" to "after", "after" to "instead_of")
            at("triggers.tr.for_each") shouldBe mapOf("before" to "row", "after" to "statement")
            at("functions.f.security") shouldBe mapOf("after" to "definer")
            at("functions.f.search_path") shouldBe mapOf("before" to "[public]", "after" to "[app, public]")
            at("custom_types.t.kind") shouldBe mapOf("before" to "enum", "after" to "domain")
            at("custom_types.t.values") shouldBe mapOf("before" to "[a]", "after" to "[a, b]")
        }

        test("column and table values use the short form of the comparison") {
            val findings = SchemaCompareFindings.of(
                tableDiff(
                    TableDiff(
                        name = "orders",
                        columnsChanged = listOf(
                            ColumnDiff(
                                name = "qty",
                                type = ValueChange(NeutralType.Integer, NeutralType.Text(maxLength = 254)),
                                default = ValueChange(null, DefaultValue.FunctionCall("current_timestamp")),
                                references = ValueChange(
                                    null,
                                    ReferenceDefinition("items", "id", onDelete = ReferentialAction.SET_NULL),
                                ),
                                generation = ValueChange(
                                    ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = "s"),
                                    null,
                                ),
                            ),
                        ),
                        primaryKey = ValueChange(listOf("id"), listOf("id", "qty")),
                        metadata = ValueChange(null, TableMetadata(engine = "InnoDB")),
                    ),
                ),
            )
            fun at(path: String) = findings.single { it["path"] == path }
            details(at("tables.orders.columns.qty.type")) shouldBe mapOf("before" to "integer", "after" to "text(254)")
            at("tables.orders.columns.qty.type")["message"] shouldBe "type changed from integer to text(254)"
            details(at("tables.orders.columns.qty.default")) shouldBe mapOf("after" to "current_timestamp()")
            details(at("tables.orders.columns.qty.references")) shouldBe
                mapOf("after" to "items.id (on_delete=set_null)")
            details(at("tables.orders.columns.qty.generation")) shouldBe
                mapOf("before" to "identity(mode=by_default,sequence=s)")
            details(at("tables.orders.primary_key")) shouldBe mapOf("before" to "[id]", "after" to "[id, qty]")
            details(at("tables.orders.metadata")) shouldBe mapOf("after" to "engine=InnoDB")
        }

        test("no finding carries the Kotlin form of an object") {
            val kotlinForm = Regex("[A-Z][A-Za-z]+\\(|\\b(AFTER|BEFORE|INSERT|UPDATE|DEFINER|ENUM|ROW)\\b")
            val findings = SchemaCompareFindings.of(
                SchemaDiff(
                    triggersChanged = listOf(
                        TriggerDiff(name = "tr", timing = ValueChange(TriggerTiming.AFTER, TriggerTiming.BEFORE)),
                    ),
                    tablesChanged = listOf(
                        TableDiff(
                            name = "t",
                            columnsChanged = listOf(
                                ColumnDiff(name = "c", type = ValueChange(NeutralType.Decimal(10, 2), NeutralType.Float())),
                            ),
                        ),
                    ),
                ),
            )
            for (finding in findings) {
                val values = details(finding).orEmpty().values + (finding["message"] as String)
                values.forEach { kotlinForm.containsMatchIn(it) shouldBe false }
            }
        }
    }
})
