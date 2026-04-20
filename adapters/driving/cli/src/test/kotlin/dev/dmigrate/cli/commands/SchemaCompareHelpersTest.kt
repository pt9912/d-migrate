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
        summary = SchemaCompareSummary(tablesAdded = 1, tablesRemoved = 1, customTypesChanged = 1),
        diff = DiffView(
            schemaMetadata = MetadataChangeView(name = StringChange("A", "B")),
            tablesAdded = listOf(TableSummaryView("new_table", 2)),
            tablesRemoved = listOf(TableSummaryView("old_table", 1)),
            customTypesChanged = listOf(CustomTypeChangeView("status", "enum",
                listOf("values: [a, b] -> [a, b, c]"))),
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
        plain shouldContain "~ status (enum)"
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
                tablesAdded = 1, tablesChanged = 1, customTypesAdded = 1),
            diff = DiffView(
                schemaMetadata = MetadataChangeView(
                    version = StringChange("1.0", "2.0")),
                customTypesAdded = listOf(CustomTypeSummaryView("status", "enum", "a, b")),
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
  Types added:     1

Schema Metadata:
  version: 1.0 -> 2.0

Custom Types:
  + status (enum): a, b

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

    test("defaultValueToString renders SequenceNextVal as readable short form") {
        SchemaCompareHelpers.defaultValueToString(DefaultValue.SequenceNextVal("invoice_seq")) shouldBe
            "sequence_nextval(invoice_seq)"
    }

    test("defaultValueToString renders null as null") {
        SchemaCompareHelpers.defaultValueToString(null) shouldBe null
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

    test("yaml columns_added includes type field matching json") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1),
            diff = DiffView(tablesChanged = listOf(TableChangeView(
                name = "t",
                columnsAdded = listOf(ColumnSummaryView("email", "email")),
            ))),
        )
        val yaml = SchemaCompareHelpers.renderYaml(doc)
        yaml shouldContain "name: \"email\""
        yaml shouldContain "type: \"email\""

        val json = SchemaCompareHelpers.renderJson(doc)
        json shouldContain """"name": "email""""
        json shouldContain """"type": "email""""
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
            customTypesChanged = listOf(CustomTypeDiff("status",
                values = ValueChange(listOf("a"), listOf("a", "b")))),
        )
        val view = SchemaCompareHelpers.projectDiff(diff)

        view.schemaMetadata!!.name!!.before shouldBe "A"
        view.tablesAdded[0].name shouldBe "t"
        view.tablesAdded[0].columnCount shouldBe 1
        view.tablesChanged[0].columnsChanged[0].type!!.before shouldBe "text(100)"
        view.tablesChanged[0].columnsChanged[0].type!!.after shouldBe "text(200)"
        view.tablesChanged[0].indicesAdded[0] shouldBe "idx_a [btree]"
        view.tablesChanged[0].constraintsAdded[0] shouldContain "uq_email"
        view.customTypesChanged[0].changes[0] shouldContain "values"
    }

    test("projectDiff handles domain custom type") {
        val diff = SchemaDiff(
            customTypesAdded = listOf(NamedCustomType("posint", CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0",
            ))),
        )
        val view = SchemaCompareHelpers.projectDiff(diff)
        view.customTypesAdded[0].name shouldBe "posint"
        view.customTypesAdded[0].kind shouldBe "domain"
        view.customTypesAdded[0].detail shouldContain "base: integer"
    }

    test("projectDiff handles composite custom type") {
        val diff = SchemaDiff(
            customTypesAdded = listOf(NamedCustomType("address", CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf("street" to ColumnDefinition(type = NeutralType.Text(200))),
            ))),
        )
        val view = SchemaCompareHelpers.projectDiff(diff)
        view.customTypesAdded[0].kind shouldBe "composite"
        view.customTypesAdded[0].detail shouldBe "1 fields"
    }

    test("projectDiff handles custom type changes with multiple change kinds") {
        val diff = SchemaDiff(
            customTypesChanged = listOf(CustomTypeDiff("posint",
                baseType = ValueChange("integer", "biginteger"),
                check = ValueChange("VALUE > 0", "VALUE >= 0"),
            )),
        )
        val view = SchemaCompareHelpers.projectDiff(diff)
        view.customTypesChanged[0].changes.size shouldBe 2
        view.customTypesChanged[0].changes[0] shouldContain "baseType"
        view.customTypesChanged[0].changes[1] shouldContain "check"
    }

    test("plain renders custom type changes") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(customTypesAdded = 1, customTypesChanged = 1),
            diff = DiffView(
                customTypesAdded = listOf(CustomTypeSummaryView("posint", "domain", "base: integer")),
                customTypesChanged = listOf(CustomTypeChangeView("status", "enum",
                    listOf("values: [a, b] -> [a, b, c]"))),
            ),
        )
        val plain = SchemaCompareHelpers.renderPlain(doc)
        plain shouldContain "Custom Types:"
        plain shouldContain "+ posint (domain): base: integer"
        plain shouldContain "~ status (enum):"
    }

    test("json renders custom types with kind") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(customTypesAdded = 1),
            diff = DiffView(
                customTypesAdded = listOf(CustomTypeSummaryView("posint", "domain", "base: integer")),
            ),
        )
        val json = SchemaCompareHelpers.renderJson(doc)
        json shouldContain """"custom_types_added"""
        json shouldContain """"kind": "domain""""
    }

    test("yaml renders custom types with kind") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(customTypesRemoved = 1),
            diff = DiffView(
                customTypesRemoved = listOf(CustomTypeSummaryView("old_type", "enum", "a, b")),
            ),
        )
        val yaml = SchemaCompareHelpers.renderYaml(doc)
        yaml shouldContain "custom_types_removed:"
        yaml shouldContain "kind: \"enum\""
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
