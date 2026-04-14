package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadResult
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class ReverseContractTest : FunSpec({

    // ─── SchemaReadOptions defaults ─────────────────────────────

    test("SchemaReadOptions defaults all include flags to true") {
        val opts = SchemaReadOptions()
        opts.includeViews shouldBe true
        opts.includeProcedures shouldBe true
        opts.includeFunctions shouldBe true
        opts.includeTriggers shouldBe true
    }

    test("SchemaReadOptions distinguishes procedures and functions as separate flags") {
        val opts = SchemaReadOptions(includeProcedures = false, includeFunctions = true)
        opts.includeProcedures shouldBe false
        opts.includeFunctions shouldBe true
    }

    // ─── SchemaReadResult contract ──────────────────────────────

    test("SchemaReadResult defaults notes and skippedObjects to empty") {
        val schema = SchemaDefinition(name = "Test", version = "1.0")
        val result = SchemaReadResult(schema = schema)
        result.notes.shouldBeEmpty()
        result.skippedObjects.shouldBeEmpty()
        result.schema.name shouldBe "Test"
    }

    test("SchemaReadResult carries notes and skippedObjects alongside schema") {
        val schema = SchemaDefinition(name = "Test", version = "1.0")
        val notes = listOf(
            SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R001",
                objectName = "spatial_ref_sys",
                message = "PostGIS system table skipped",
                hint = "Enable PostGIS extension tracking to include",
            ),
            SchemaReadNote(
                severity = SchemaReadSeverity.INFO,
                code = "R002",
                objectName = "users.email",
                message = "varchar(254) mapped to email type",
            ),
        )
        val skipped = listOf(
            SkippedObject(
                type = "TABLE",
                name = "sqlite_stat1",
                reason = "SQLite system table",
                code = "S001",
            ),
        )

        val result = SchemaReadResult(schema = schema, notes = notes, skippedObjects = skipped)
        result.notes shouldHaveSize 2
        result.notes[0].severity shouldBe SchemaReadSeverity.WARNING
        result.notes[0].code shouldBe "R001"
        result.notes[0].hint shouldBe "Enable PostGIS extension tracking to include"
        result.notes[1].severity shouldBe SchemaReadSeverity.INFO
        result.notes[1].hint shouldBe null
        result.skippedObjects shouldHaveSize 1
        result.skippedObjects[0].type shouldBe "TABLE"
        result.skippedObjects[0].name shouldBe "sqlite_stat1"
    }

    // ─── SchemaReadNote semantics ───────────────────────────────

    test("SchemaReadNote has all required fields") {
        val note = SchemaReadNote(
            severity = SchemaReadSeverity.ACTION_REQUIRED,
            code = "R100",
            objectName = "my_proc",
            message = "Unsupported parameter mode",
            hint = "Review manually",
        )
        note.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
        note.code shouldBe "R100"
        note.objectName shouldBe "my_proc"
        note.message shouldBe "Unsupported parameter mode"
        note.hint shouldBe "Review manually"
    }

    test("SchemaReadSeverity has exactly three values") {
        SchemaReadSeverity.entries shouldHaveSize 3
        SchemaReadSeverity.entries.map { it.name } shouldBe
            listOf("INFO", "WARNING", "ACTION_REQUIRED")
    }

    // ─── ResolvedSchemaOperand contract ─────────────────────────

    test("ResolvedSchemaOperand preserves all fields") {
        val schema = SchemaDefinition(name = "Live DB", version = "1.0")
        val validation = ValidationResult(
            warnings = listOf(ValidationWarning("E008", "Table has no primary key", "tables.log"))
        )
        val notes = listOf(
            SchemaReadNote(
                severity = SchemaReadSeverity.WARNING,
                code = "R001",
                objectName = "vtable",
                message = "Virtual table skipped",
            ),
        )
        val skipped = listOf(
            SkippedObject(type = "TABLE", name = "sqlite_stat1", reason = "System table"),
        )

        val operand = ResolvedSchemaOperand(
            reference = "db:staging",
            schema = schema,
            validation = validation,
            notes = notes,
            skippedObjects = skipped,
        )

        operand.reference shouldBe "db:staging"
        operand.schema.name shouldBe "Live DB"
        operand.validation.isValid shouldBe true
        operand.validation.warnings shouldHaveSize 1
        operand.notes shouldHaveSize 1
        operand.notes[0].code shouldBe "R001"
        operand.skippedObjects shouldHaveSize 1
        operand.skippedObjects[0].name shouldBe "sqlite_stat1"
    }

    test("ResolvedSchemaOperand defaults notes and skippedObjects to empty") {
        val operand = ResolvedSchemaOperand(
            reference = "/tmp/schema.yaml",
            schema = SchemaDefinition(name = "T", version = "1.0"),
            validation = ValidationResult(),
        )
        operand.notes.shouldBeEmpty()
        operand.skippedObjects.shouldBeEmpty()
    }
})
