package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.*
import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SchemaCompareHelpersTest : FunSpec({

    val identicalDoc = SchemaCompareDocument(
        status = "identical", exitCode = 0,
        source = "a.yaml", target = "b.yaml",
        summary = SchemaCompareSummary(), diff = null,
    )

    val differentDoc = SchemaCompareDocument(
        status = "different", exitCode = 1,
        source = "a.yaml", target = "b.yaml",
        summary = SchemaCompareSummary(tablesAdded = 1, tablesRemoved = 1, enumTypesChanged = 1),
        diff = DiffView(
            schemaMetadata = MetadataChangeView(name = StringChange("A", "B")),
            tablesAdded = listOf(TableSummaryView("new_table", 2)),
            tablesRemoved = listOf(TableSummaryView("old_table", 1)),
            enumTypesChanged = listOf(EnumChangeView("status",
                listOf("a", "b"), listOf("a", "b", "c"))),
        ),
    )

    val invalidDoc = SchemaCompareDocument(
        status = "invalid", exitCode = 3,
        source = "a.yaml", target = "b.yaml",
        summary = SchemaCompareSummary(), diff = null,
        validation = CompareValidation(
            source = ValidationResult(errors = listOf(
                ValidationError("E001", "no columns", "tables.t"))),
            target = ValidationResult(warnings = listOf(
                ValidationWarning("W001", "some warning", "tables.t"))),
        ),
    )

    // --- Summary counts ---

    test("summary total changes counts correctly") {
        differentDoc.summary.totalChanges shouldBe 3
    }

    // --- Plain-Text ---

    test("plain identical shows IDENTICAL status") {
        val plain = SchemaCompareHelpers.renderPlain(identicalDoc)
        plain shouldContain "IDENTICAL"
        plain shouldContain "No differences found"
    }

    test("plain different shows DIFFERENT status with details") {
        val plain = SchemaCompareHelpers.renderPlain(differentDoc)
        plain shouldContain "DIFFERENT"
        plain shouldContain "3 change(s)"
        plain shouldContain "+ new_table"
        plain shouldContain "- old_table"
        plain shouldContain "~ status"
        plain shouldContain "name: A -> B"
    }

    test("plain invalid shows INVALID status with errors") {
        val plain = SchemaCompareHelpers.renderPlain(invalidDoc)
        plain shouldContain "INVALID"
        plain shouldContain "Error [source] [E001]"
        plain shouldContain "Warning [target] [W001]"
    }

    test("plain is deterministic") {
        val first = SchemaCompareHelpers.renderPlain(differentDoc)
        val second = SchemaCompareHelpers.renderPlain(differentDoc)
        first shouldBe second
    }

    // m2: Golden-snapshot test for plain text
    test("plain text golden snapshot for realistic diff") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "old.yaml", target = "new.yaml",
            summary = SchemaCompareSummary(
                tablesAdded = 1, tablesChanged = 1, enumTypesAdded = 1),
            diff = DiffView(
                schemaMetadata = MetadataChangeView(
                    version = StringChange("1.0", "2.0")),
                enumTypesAdded = listOf(EnumSummaryView("status", listOf("a", "b"))),
                tablesAdded = listOf(TableSummaryView("orders", 3)),
                tablesChanged = listOf(TableChangeView(
                    name = "users",
                    columnsAdded = listOf(ColumnSummaryView("email", "email")),
                    columnsChanged = listOf(ColumnChangeView(
                        name = "name",
                        type = StringChange("text(100)", "text(200)"),
                    )),
                    indicesAdded = listOf("idx_email [btree]"),
                )),
            ),
        )
        val expected = """
Schema Compare: old.yaml <-> new.yaml

Status: DIFFERENT

Summary: 3 change(s)
  Tables added:    1
  Tables changed:  1
  Enums added:     1

Schema Metadata:
  version: 1.0 -> 2.0

Enum Types:
  + status: [a, b]

Tables:
  + orders (3 columns)
  ~ users:
      + column email: email
      ~ column name:
          type: text(100) -> text(200)
      + index idx_email [btree]""".trimIndent()

        SchemaCompareHelpers.renderPlain(doc) shouldBe expected
    }

    // --- JSON ---

    test("json identical contains stable command/status/summary") {
        val json = SchemaCompareHelpers.renderJson(identicalDoc)
        json shouldContain """"command": "schema.compare""""
        json shouldContain """"status": "identical""""
        json shouldContain """"exit_code": 0"""
        json shouldContain """"tables_added": 0"""
        json shouldContain """"diff": null"""
    }

    test("json different contains diff block") {
        val json = SchemaCompareHelpers.renderJson(differentDoc)
        json shouldContain """"status": "different""""
        json shouldContain """"tables_added": ["new_table"]"""
        json shouldContain """"tables_removed": ["old_table"]"""
        json shouldContain """"schema_metadata""""
    }

    test("json invalid contains validation block") {
        val json = SchemaCompareHelpers.renderJson(invalidDoc)
        json shouldContain """"status": "invalid""""
        json shouldContain """"validation""""
        json shouldContain """"source""""
        json shouldContain """"E001""""
    }

    test("json renders indices and constraints for changed tables") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1),
            diff = DiffView(tablesChanged = listOf(TableChangeView(
                name = "t",
                indicesAdded = listOf("idx_a [btree]"),
                constraintsRemoved = listOf("fk_old (foreign_key on [ref])"),
            ))),
        )
        val json = SchemaCompareHelpers.renderJson(doc)
        json shouldContain """"indices_added""""
        json shouldContain "idx_a [btree]"
        json shouldContain """"constraints_removed""""
        json shouldContain "fk_old"
    }

    test("json renders default values with proper null handling") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1),
            diff = DiffView(tablesChanged = listOf(TableChangeView(
                name = "t",
                columnsChanged = listOf(ColumnChangeView(
                    name = "c",
                    default = NullableStringChange(null, "\"hello\""),
                )),
            ))),
        )
        val json = SchemaCompareHelpers.renderJson(doc)
        json shouldContain """"before": null"""
        // The display string "hello" (with quotes) is JSON-escaped to \"hello\"
        json shouldContain """"default": {"before": null, "after": """
    }

    // --- YAML ---

    test("yaml identical contains stable fields") {
        val yaml = SchemaCompareHelpers.renderYaml(identicalDoc)
        yaml shouldContain "command: schema.compare"
        yaml shouldContain "status: identical"
        yaml shouldContain "exit_code: 0"
        yaml shouldContain "diff: null"
    }

    test("yaml different contains diff block") {
        val yaml = SchemaCompareHelpers.renderYaml(differentDoc)
        yaml shouldContain "status: different"
        yaml shouldContain "tables_added:"
        yaml shouldContain "\"new_table\""
    }

    test("yaml has same fields as json") {
        val json = SchemaCompareHelpers.renderJson(differentDoc)
        val yaml = SchemaCompareHelpers.renderYaml(differentDoc)
        for (field in listOf("schema.compare", "different", "new_table", "old_table")) {
            json shouldContain field
            yaml shouldContain field
        }
    }

    test("yaml renders indices and constraints for changed tables") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1),
            diff = DiffView(tablesChanged = listOf(TableChangeView(
                name = "t",
                indicesAdded = listOf("idx_a [btree]"),
                constraintsAdded = listOf("uq_email (unique on [email])"),
            ))),
        )
        val yaml = SchemaCompareHelpers.renderYaml(doc)
        yaml shouldContain "indices_added:"
        yaml shouldContain "constraints_added:"
    }

    test("yaml renders view change fields") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(viewsChanged = 1),
            diff = DiffView(viewsChanged = listOf(ViewChangeView(
                name = "v",
                materialized = StringChange("false", "true"),
                refresh = NullableStringChange(null, "on_demand"),
                sourceDialect = NullableStringChange("postgresql", "mysql"),
            ))),
        )
        val yaml = SchemaCompareHelpers.renderYaml(doc)
        yaml shouldContain "materialized:"
        yaml shouldContain "refresh:"
        yaml shouldContain "source_dialect:"
    }

    // --- Validation block for warnings without errors ---

    test("validation block rendered for warnings on valid schemas") {
        val doc = SchemaCompareDocument(
            status = "identical", exitCode = 0,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(), diff = null,
            validation = CompareValidation(
                source = ValidationResult(warnings = listOf(
                    ValidationWarning("W001", "warn", "tables.t"))),
                target = ValidationResult(),
            ),
        )
        val json = SchemaCompareHelpers.renderJson(doc)
        json shouldContain """"validation""""
        json shouldContain """"W001""""
    }

    // --- Canonical signatures ---

    test("index signature is stable") {
        val idx = IndexDefinition(name = "idx_a", columns = listOf("a"), type = IndexType.BTREE)
        SchemaCompareHelpers.indexSignature(idx) shouldBe "idx_a [btree]"
    }

    test("unnamed index signature uses columns") {
        val idx = IndexDefinition(name = null, columns = listOf("a", "b"),
            type = IndexType.HASH, unique = true)
        SchemaCompareHelpers.indexSignature(idx) shouldBe "a,b [hash,unique]"
    }

    test("constraint signature is stable") {
        val c = ConstraintDefinition(name = "fk_order", type = ConstraintType.FOREIGN_KEY,
            columns = listOf("order_id"),
            references = ConstraintReferenceDefinition(table = "orders", columns = listOf("id")))
        val sig = SchemaCompareHelpers.constraintSignature(c)
        sig shouldContain "fk_order"
        sig shouldContain "foreign_key"
        sig shouldContain "orders"
    }

    // --- Special characters (m1: stronger assertion) ---

    test("json escapes special characters in names") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "file \"a\".yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesAdded = 1),
            diff = DiffView(tablesAdded = listOf(TableSummaryView("table\nname", 1))),
        )
        val json = SchemaCompareHelpers.renderJson(doc)
        // Verify the raw JSON escapes quotes and newlines
        json shouldContain "file \\\"a\\\".yaml"
        // Newline in name must be escaped to literal \n in JSON output
        json shouldNotContain "table\nname"  // must NOT contain actual newline
        json shouldContain "table"
        json shouldContain "name"
    }

    test("yaml escapes special characters in names") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesAdded = 1),
            diff = DiffView(tablesAdded = listOf(TableSummaryView("table\"name", 1))),
        )
        val yaml = SchemaCompareHelpers.renderYaml(doc)
        yaml shouldContain """table\"name"""
    }

    // --- Column changed rendering ---

    test("plain renders column type change with canonical type strings") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1),
            diff = DiffView(tablesChanged = listOf(TableChangeView(
                name = "users",
                columnsChanged = listOf(ColumnChangeView(
                    name = "email",
                    type = StringChange("text(100)", "text(200)"),
                )),
            ))),
        )
        val plain = SchemaCompareHelpers.renderPlain(doc)
        plain shouldContain "text(100) -> text(200)"
    }

    // --- Projection ---

    test("projectDiff converts SchemaDiff to DiffView with canonical strings") {
        val diff = SchemaDiff(
            schemaMetadata = SchemaMetadataDiff(name = ValueChange("A", "B")),
            tablesAdded = listOf(NamedTable("t", TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true)))))),
            tablesChanged = listOf(TableDiff(
                name = "users",
                columnsChanged = listOf(ColumnDiff(
                    name = "name",
                    type = ValueChange(NeutralType.Text(100), NeutralType.Text(200)),
                )),
                indicesAdded = listOf(IndexDefinition(name = "idx_a", columns = listOf("a"))),
                constraintsAdded = listOf(ConstraintDefinition(
                    name = "uq_email", type = ConstraintType.UNIQUE, columns = listOf("email"))),
            )),
            enumTypesChanged = listOf(EnumTypeDiff("status", ValueChange(
                listOf("a"), listOf("a", "b")))),
        )
        val view = SchemaCompareHelpers.projectDiff(diff)

        view.schemaMetadata!!.name!!.before shouldBe "A"
        view.tablesAdded[0].name shouldBe "t"
        view.tablesAdded[0].columnCount shouldBe 1
        view.tablesChanged[0].columnsChanged[0].type!!.before shouldBe "text(100)"
        view.tablesChanged[0].columnsChanged[0].type!!.after shouldBe "text(200)"
        view.tablesChanged[0].indicesAdded[0] shouldBe "idx_a [btree]"
        view.tablesChanged[0].constraintsAdded[0] shouldContain "uq_email"
        view.enumTypesChanged[0].after shouldBe listOf("a", "b")
    }

    test("projectDiff handles ColumnDiff.unique and references") {
        val diff = SchemaDiff(tablesChanged = listOf(TableDiff(
            name = "t",
            columnsChanged = listOf(ColumnDiff(
                name = "c",
                unique = ValueChange(false, true),
                references = ValueChange(
                    ReferenceDefinition("old", "id"),
                    null,
                ),
            )),
        )))
        val view = SchemaCompareHelpers.projectDiff(diff)
        val col = view.tablesChanged[0].columnsChanged[0]
        col.unique!!.before shouldBe "false"
        col.unique!!.after shouldBe "true"
        col.references!!.before shouldContain "old.id"
        col.references!!.after shouldBe null
    }
})
