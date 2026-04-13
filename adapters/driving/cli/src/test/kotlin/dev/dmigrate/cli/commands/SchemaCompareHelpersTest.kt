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
        diff = SchemaDiff(
            schemaMetadata = SchemaMetadataDiff(name = ValueChange("A", "B")),
            tablesAdded = listOf(NamedTable("new_table", TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(true))),
            ))),
            tablesRemoved = listOf(NamedTable("old_table", TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer)),
            ))),
            enumTypesChanged = listOf(EnumTypeDiff("status", ValueChange(
                listOf("a", "b"), listOf("a", "b", "c"),
            ))),
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
        yaml shouldContain "- new_table"
    }

    test("yaml has same fields as json") {
        val json = SchemaCompareHelpers.renderJson(differentDoc)
        val yaml = SchemaCompareHelpers.renderYaml(differentDoc)
        // Both must contain the core fields
        for (field in listOf("schema.compare", "different", "new_table", "old_table")) {
            json shouldContain field
            yaml shouldContain field
        }
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
        val idx = IndexDefinition(name = null, columns = listOf("a", "b"), type = IndexType.HASH, unique = true)
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

    // --- Special characters ---

    test("json escapes special characters in names") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "file \"a\".yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesAdded = 1),
            diff = SchemaDiff(tablesAdded = listOf(NamedTable("table\nname", TableDefinition(
                columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer)),
            )))),
        )
        val json = SchemaCompareHelpers.renderJson(doc)
        json shouldContain """file \"a\".yaml"""
        json shouldNotContain "\n\"table"  // newline should be escaped
    }

    // --- Column changed rendering ---

    test("plain renders column type change with canonical type strings") {
        val doc = SchemaCompareDocument(
            status = "different", exitCode = 1,
            source = "a.yaml", target = "b.yaml",
            summary = SchemaCompareSummary(tablesChanged = 1),
            diff = SchemaDiff(tablesChanged = listOf(TableDiff(
                name = "users",
                columnsChanged = listOf(ColumnDiff(
                    name = "email",
                    type = ValueChange(NeutralType.Text(100), NeutralType.Text(200)),
                )),
            ))),
        )
        val plain = SchemaCompareHelpers.renderPlain(doc)
        plain shouldContain "text(100) -> text(200)"
    }
})
