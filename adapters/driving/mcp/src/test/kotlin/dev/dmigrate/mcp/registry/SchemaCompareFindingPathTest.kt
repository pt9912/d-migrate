package dev.dmigrate.mcp.registry

import dev.dmigrate.cli.commands.ComputedExpressionDecidability
import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.NamedCustomType
import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.NamedView
import dev.dmigrate.core.diff.ProcedureDiff
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SchemaMetadataDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.TriggerDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TableMetadata
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

/**
 * Das **eine** Pfad-Schema aller `schema_compare`-Funde (`spec/cli-spec.md`).
 *
 * Ein nicht-trivialer Vergleich erzeugt jede Fund-Art, die der Handler kennt;
 * jeder `path` muss der Grammatik unten genuegen. Die Grammatik ist der
 * Dokument-Pfad des neutralen Schemas: Abschnitt, Objektname, bei Tabellen
 * der Unterabschnitt, und — wo der Vergleich es kennt — das geaenderte Feld.
 */
class SchemaCompareFindingPathTest : FunSpec({

    val name = "[^.]+"
    // `.expression` gibt es nur unter `generation` (W137).
    val columnFields = "type|required|unique|default|references|generation(\\.expression)?"
    val tablePath = "tables\\.$name(\\.primary_key|\\.metadata|\\.columns\\.$name(\\.($columnFields))?" +
        "|\\.indices\\.$name|\\.constraints\\.$name)?"
    val sectionFields = mapOf(
        "views" to "columns|query|materialized|refresh|source_dialect",
        "sequences" to "start|increment|min_value|max_value|cycle|cache",
        "custom_types" to "kind|values|base_type|precision|scale|check|description|fields",
        "functions" to "parameters|returns|language|deterministic|body|source_dialect|security|definer|search_path|sql_mode",
        "procedures" to "parameters|language|body|source_dialect|security|definer|search_path|sql_mode",
        "triggers" to "table|event|timing|for_each|condition|body|source_dialect",
    )
    val objectPaths = sectionFields.entries.joinToString("|") { (section, fields) -> "$section\\.$name(\\.($fields))?" }
    val grammar = Regex("^(name|version|$tablePath|$objectPaths)$")

    /** Aenderungsfunde, deren Pfad auf dem geaenderten Feld enden muss. */
    val fieldLevelCodes = Regex(
        "^(VIEW|SEQUENCE|CUSTOM_TYPE|FUNCTION|PROCEDURE|TRIGGER)_CHANGED$|^TABLE_COLUMN_.*_(CHANGED|TIGHTENED|RELAXED)$",
    )

    fun column(type: NeutralType = NeutralType.Integer) = ColumnDefinition(type = type)
    val index = IndexDefinition(name = "ix_status", columns = listOf(IndexColumn("status")))
    val unnamedIndex = IndexDefinition(columns = listOf(IndexColumn("a"), IndexColumn("b")))

    /** Ein unbenannter Index ueber einem Ausdruck mit Punkt, Klammern, Richtung und Praefix. */
    val expressionIndex = IndexDefinition(
        columns = listOf(
            IndexColumn.expression("lower(t.email)", IndexSortDirection.DESC),
            IndexColumn("code", prefixLength = 10),
        ),
    )
    val check = ConstraintDefinition(name = "ck_qty", type = ConstraintType.CHECK, expression = "qty > 0")
    val view = ViewDefinition(query = "SELECT 1")
    val sequence = SequenceDefinition()
    val customType = CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))
    val function = FunctionDefinition(body = "BEGIN RETURN 1; END;")
    val procedure = ProcedureDefinition(body = "BEGIN NULL; END;")
    val trigger = TriggerDefinition(table = "orders", events = setOf(TriggerEvent.INSERT), timing = TriggerTiming.AFTER)

    val diff = SchemaDiff(
        schemaMetadata = SchemaMetadataDiff(name = ValueChange("a", "b"), version = ValueChange("1", "2")),
        tablesAdded = listOf(NamedTable("added_table", TableDefinition(columns = mapOf("id" to column())))),
        tablesRemoved = listOf(NamedTable("removed_table", TableDefinition(columns = mapOf("id" to column())))),
        tablesChanged = listOf(
            TableDiff(
                name = "orders",
                columnsAdded = mapOf("note" to column()),
                columnsRemoved = mapOf("legacy" to column()),
                columnsChanged = listOf(
                    ColumnDiff(
                        name = "qty",
                        type = ValueChange(NeutralType.Integer, NeutralType.BigInteger),
                        required = ValueChange(false, true),
                        unique = ValueChange(false, true),
                        default = ValueChange(null, DefaultValue.NumberLiteral(0)),
                        references = ValueChange(null, ReferenceDefinition("items", "id")),
                        generation = ValueChange(null, ColumnGeneration.Identity()),
                    ),
                    ColumnDiff(name = "status", required = ValueChange(true, false), unique = ValueChange(true, false)),
                ),
                primaryKey = ValueChange(listOf("id"), listOf("id", "qty")),
                indicesAdded = listOf(index, expressionIndex),
                indicesRemoved = listOf(unnamedIndex),
                indicesChanged = listOf(ValueChange(index, index.copy(unique = true))),
                constraintsAdded = listOf(check.copy(name = "ck_new")),
                constraintsRemoved = listOf(check.copy(name = "ck_old")),
                constraintsChanged = listOf(ValueChange(check, check.copy(expression = "qty > 1"))),
                metadata = ValueChange(null, TableMetadata(engine = "InnoDB")),
            ),
        ),
        viewsAdded = listOf(NamedView("v_added", view)),
        viewsRemoved = listOf(NamedView("v_removed", view)),
        viewsChanged = listOf(
            ViewDiff(
                name = "v_orders",
                columns = ValueChange(listOf("id"), listOf("id", "qty")),
                query = ValueChange("SELECT 1", "SELECT 2"),
                materialized = ValueChange(false, true),
                refresh = ValueChange(null, "on_demand"),
            ),
        ),
        sequencesAdded = listOf(NamedSequence("s_added", sequence)),
        sequencesRemoved = listOf(NamedSequence("s_removed", sequence)),
        sequencesChanged = listOf(
            SequenceDiff(name = "s_orders", start = ValueChange(1L, 10L), cycle = ValueChange(false, true)),
        ),
        customTypesAdded = listOf(NamedCustomType("t_added", customType)),
        customTypesRemoved = listOf(NamedCustomType("t_removed", customType)),
        customTypesChanged = listOf(
            CustomTypeDiff(name = "t_status", values = ValueChange(listOf("a"), listOf("a", "b"))),
        ),
        functionsAdded = listOf(NamedFunction("f_added", function)),
        functionsRemoved = listOf(NamedFunction("f_removed", function)),
        functionsChanged = listOf(
            FunctionDiff(name = "f_total", body = ValueChange("a", "b"), searchPath = ValueChange(null, listOf("public"))),
        ),
        proceduresAdded = listOf(NamedProcedure("p_added", procedure)),
        proceduresRemoved = listOf(NamedProcedure("p_removed", procedure)),
        proceduresChanged = listOf(ProcedureDiff(name = "p_sync", sqlMode = ValueChange(null, "ANSI"))),
        triggersAdded = listOf(NamedTrigger("tr_added", trigger)),
        triggersRemoved = listOf(NamedTrigger("tr_removed", trigger)),
        triggersChanged = listOf(
            TriggerDiff(name = "tr_audit", timing = ValueChange(TriggerTiming.AFTER, TriggerTiming.BEFORE)),
        ),
    )

    val undecided = ComputedExpressionDecidability.diagnostics(
        current = computedSchema("quantity * unit_price"),
        desired = computedSchema("((quantity)::numeric * unit_price)"),
        authorship = null,
        serverForm = null,
    )

    val findings = SchemaCompareFindings.of(diff) + undecided.map(SchemaCompareFindings::undecided)

    test("the comparison covers every finding kind the projection knows") {
        findings.map { it["code"] } shouldContainAll listOf(
            "SCHEMA_NAME_CHANGED", "SCHEMA_VERSION_CHANGED",
            "TABLE_ADDED", "TABLE_REMOVED",
            "TABLE_COLUMN_ADDED", "TABLE_COLUMN_REMOVED", "TABLE_COLUMN_TYPE_CHANGED",
            "TABLE_COLUMN_REQUIRED_TIGHTENED", "TABLE_COLUMN_REQUIRED_RELAXED",
            "TABLE_COLUMN_UNIQUE_TIGHTENED", "TABLE_COLUMN_UNIQUE_RELAXED",
            "TABLE_COLUMN_DEFAULT_CHANGED", "TABLE_COLUMN_REFERENCES_CHANGED", "TABLE_COLUMN_GENERATION_CHANGED",
            "TABLE_PRIMARY_KEY_CHANGED",
            "TABLE_INDEX_ADDED", "TABLE_INDEX_REMOVED", "TABLE_INDEX_CHANGED",
            "TABLE_CONSTRAINT_ADDED", "TABLE_CONSTRAINT_REMOVED", "TABLE_CONSTRAINT_CHANGED",
            "TABLE_METADATA_CHANGED",
            "VIEW_ADDED", "VIEW_REMOVED", "VIEW_CHANGED",
            "SEQUENCE_ADDED", "SEQUENCE_REMOVED", "SEQUENCE_CHANGED",
            "CUSTOM_TYPE_ADDED", "CUSTOM_TYPE_REMOVED", "CUSTOM_TYPE_CHANGED",
            "FUNCTION_ADDED", "FUNCTION_REMOVED", "FUNCTION_CHANGED",
            "PROCEDURE_ADDED", "PROCEDURE_REMOVED", "PROCEDURE_CHANGED",
            "TRIGGER_ADDED", "TRIGGER_REMOVED", "TRIGGER_CHANGED",
            ComputedExpressionDecidability.UNDECIDED,
        )
    }

    test("every path follows the one schema") {
        for (finding in findings) {
            withClue("${finding["code"]} -> ${finding["path"]}") {
                grammar.matches(finding["path"] as String) shouldBe true
            }
        }
    }

    test("a change finding ends at the changed field wherever the comparison knows it") {
        for (finding in findings.filter { fieldLevelCodes.matches(it["code"] as String) }) {
            val path = finding["path"] as String
            val segments = path.split('.')
            withClue("${finding["code"]} -> $path") {
                val expected = if (path.startsWith("tables.")) 5 else 3
                segments.size shouldBe expected
            }
        }
    }

    test("an object with several changed fields yields one finding per field") {
        findings.filter { it["code"] == "VIEW_CHANGED" }.map { it["path"] } shouldBe listOf(
            "views.v_orders.columns", "views.v_orders.query", "views.v_orders.materialized", "views.v_orders.refresh",
        )
        findings.filter { it["code"] == "SEQUENCE_CHANGED" }.map { it["path"] } shouldBe
            listOf("sequences.s_orders.start", "sequences.s_orders.cycle")
    }

    test("the schema metadata sits at the top level of the document") {
        findings.single { it["code"] == "SCHEMA_NAME_CHANGED" }["path"] shouldBe "name"
        findings.single { it["code"] == "SCHEMA_VERSION_CHANGED" }["path"] shouldBe "version"
    }

    test("an unnamed index is addressed by its keys") {
        findings.single { it["code"] == "TABLE_INDEX_REMOVED" }["path"] shouldBe "tables.orders.indices.a,b"
    }

    test("an expression key appears as its identifier short form, without dot, direction or prefix") {
        val added = findings.filter { it["code"] == "TABLE_INDEX_ADDED" }.map { it["path"] }
        added shouldContainAll listOf("tables.orders.indices.ix_status", "tables.orders.indices.lower_t_email,code")
        added.forEach { grammar.matches(it as String) shouldBe true }
    }

    context("die Ausgabe des echten Comparators") {

        // Zwei Schemata, die sich in jeder Art unterscheiden, die der
        // Comparator melden kann — durch denselben Comparator und dieselbe
        // Diagnose, die `schema_compare` benutzt.
        val left = realSchema(before = true)
        val right = realSchema(before = false)
        val realDiff = SchemaComparator(canonicalizeRawExpressions = true).compare(left, right)
        val realFindings = SchemaCompareFindings.of(realDiff) +
            ComputedExpressionDecidability.diagnostics(left, right, authorship = null, serverForm = null)
                .map(SchemaCompareFindings::undecided)

        test("the comparison yields every finding kind the comparator can produce") {
            // Nicht darunter: TABLE_COLUMN_UNIQUE_* und
            // TABLE_COLUMN_REFERENCES_CHANGED — der Comparator fuehrt ein
            // einspaltiges UNIQUE und einen einspaltigen Fremdschluessel als
            // Constraint und meldet sie dort.
            realFindings.map { it["code"] }.toSet() shouldContainAll listOf(
                "SCHEMA_NAME_CHANGED", "SCHEMA_VERSION_CHANGED",
                "TABLE_ADDED", "TABLE_REMOVED",
                "TABLE_COLUMN_ADDED", "TABLE_COLUMN_REMOVED", "TABLE_COLUMN_TYPE_CHANGED",
                "TABLE_COLUMN_REQUIRED_TIGHTENED", "TABLE_COLUMN_REQUIRED_RELAXED",
                "TABLE_COLUMN_DEFAULT_CHANGED", "TABLE_COLUMN_GENERATION_CHANGED",
                "TABLE_PRIMARY_KEY_CHANGED",
                "TABLE_INDEX_ADDED", "TABLE_INDEX_REMOVED", "TABLE_INDEX_CHANGED",
                "TABLE_CONSTRAINT_ADDED", "TABLE_CONSTRAINT_REMOVED", "TABLE_CONSTRAINT_CHANGED",
                "TABLE_METADATA_CHANGED",
                "VIEW_ADDED", "VIEW_REMOVED", "VIEW_CHANGED",
                "SEQUENCE_ADDED", "SEQUENCE_REMOVED", "SEQUENCE_CHANGED",
                "CUSTOM_TYPE_ADDED", "CUSTOM_TYPE_REMOVED", "CUSTOM_TYPE_CHANGED",
                "FUNCTION_ADDED", "FUNCTION_REMOVED", "FUNCTION_CHANGED",
                "PROCEDURE_ADDED", "PROCEDURE_REMOVED", "PROCEDURE_CHANGED",
                "TRIGGER_ADDED", "TRIGGER_REMOVED", "TRIGGER_CHANGED",
                ComputedExpressionDecidability.UNDECIDED,
            )
        }

        test("every path of the real comparison follows the one schema") {
            for (finding in realFindings) {
                withClue("${finding["code"]} -> ${finding["path"]}") {
                    grammar.matches(finding["path"] as String) shouldBe true
                }
            }
        }

        test("the real comparison ends change findings at the field") {
            for (finding in realFindings.filter { fieldLevelCodes.matches(it["code"] as String) }) {
                val path = finding["path"] as String
                withClue("${finding["code"]} -> $path") {
                    path.split('.').size shouldBe if (path.startsWith("tables.")) 5 else 3
                }
            }
        }
    }

    test("`.expression` only follows `generation`") {
        grammar.matches("tables.t.columns.c.generation.expression") shouldBe true
        grammar.matches("tables.t.columns.c.default.expression") shouldBe false
        grammar.matches("tables.t.columns.c.type.expression") shouldBe false
    }
})

private fun computedSchema(expression: String) = SchemaDefinition(
    name = "app", version = "1",
    tables = mapOf(
        "order_line" to TableDefinition(
            columns = mapOf(
                "total" to ColumnDefinition(
                    type = NeutralType.Decimal(10, 2),
                    generation = ColumnGeneration.Computed(expression, stored = true),
                ),
            ),
        ),
    ),
)

/**
 * Ein Schema in zwei Fassungen, die sich in jeder Fund-Art unterscheiden.
 * [before] waehlt die Fassung.
 */
private fun realSchema(before: Boolean): SchemaDefinition {
    fun pick(a: String, b: String) = if (before) a else b
    val orders = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "qty" to ColumnDefinition(
                if (before) NeutralType.Integer else NeutralType.BigInteger,
                required = !before,
                default = if (before) null else DefaultValue.NumberLiteral(0),
            ),
            "note" to ColumnDefinition(NeutralType.Text(), required = before),
            "serial" to ColumnDefinition(
                NeutralType.BigInteger,
                generation = ColumnGeneration.Identity(
                    mode = if (before) IdentityMode.BY_DEFAULT else IdentityMode.ALWAYS,
                ),
            ),
            "total" to ColumnDefinition(
                NeutralType.Decimal(10, 2),
                generation = ColumnGeneration.Computed(pick("qty * 2", "(qty)::numeric * 2"), stored = true),
            ),
            pick("legacy", "added") to ColumnDefinition(NeutralType.Text()),
        ),
        primaryKey = if (before) listOf("id") else listOf("id", "qty"),
        indices = listOf(
            IndexDefinition(name = "ix_note", columns = listOf(IndexColumn("note")), unique = !before),
            IndexDefinition(name = pick("ix_old", "ix_new"), columns = listOf(IndexColumn("qty"))),
            IndexDefinition(columns = listOf(IndexColumn.expression(pick("lower(note)", "upper(orders.note)")))),
        ),
        constraints = listOf(
            ConstraintDefinition(name = "ck_qty", type = ConstraintType.CHECK, expression = pick("qty > 0", "qty > 1")),
            ConstraintDefinition(
                name = pick("ck_old", "ck_new"), type = ConstraintType.CHECK, expression = "note <> ''",
            ),
        ),
        metadata = TableMetadata(engine = pick("InnoDB", "MyISAM")),
    )
    return SchemaDefinition(
        name = pick("shop", "store"),
        version = pick("1", "2"),
        tables = mapOf(
            "orders" to orders,
            pick("gone", "fresh") to TableDefinition(columns = mapOf("id" to ColumnDefinition(NeutralType.Integer))),
        ),
        views = mapOf(
            "v_orders" to ViewDefinition(query = pick("SELECT id FROM orders", "SELECT qty FROM orders"), materialized = !before),
            pick("v_gone", "v_fresh") to ViewDefinition(query = "SELECT 1"),
        ),
        sequences = mapOf(
            "s_orders" to SequenceDefinition(start = if (before) 1L else 10L),
            pick("s_gone", "s_fresh") to SequenceDefinition(),
        ),
        customTypes = mapOf(
            "t_status" to CustomTypeDefinition(
                kind = CustomTypeKind.ENUM,
                values = if (before) listOf("a") else listOf("a", "b"),
            ),
            pick("t_gone", "t_fresh") to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("x")),
        ),
        functions = mapOf(
            "f_total" to FunctionDefinition(body = pick("RETURN 1;", "RETURN 2;")),
            pick("f_gone", "f_fresh") to FunctionDefinition(body = "RETURN 0;"),
        ),
        procedures = mapOf(
            "p_sync" to ProcedureDefinition(body = "BEGIN NULL; END;", sqlMode = if (before) null else "ANSI"),
            pick("p_gone", "p_fresh") to ProcedureDefinition(body = "BEGIN NULL; END;"),
        ),
        triggers = mapOf(
            "tr_audit" to TriggerDefinition(
                table = "orders", events = setOf(TriggerEvent.INSERT),
                timing = if (before) TriggerTiming.AFTER else TriggerTiming.BEFORE,
            ),
            pick("tr_gone", "tr_fresh") to TriggerDefinition(
                table = "orders", events = setOf(TriggerEvent.UPDATE), timing = TriggerTiming.AFTER,
            ),
        ),
    )
}
