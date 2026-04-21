package dev.dmigrate.cli.commands

import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class CompareRendererOperandTest : FunSpec({

    val w116Note = SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "W116",
        objectName = "dmg_sequences",
        message = "Degraded sequence emulation detected",
    )

    val skippedTrigger = SkippedObject(
        type = "trigger",
        name = "trg_users_id_seq",
        reason = "Support-trigger not representable in neutral model",
        code = "W116",
    )

    val operandWithNotes = OperandInfo(
        reference = "/tmp/reverse.yaml",
        notes = listOf(w116Note),
        skippedObjects = listOf(skippedTrigger),
    )

    val emptyOperand = OperandInfo(reference = "/tmp/clean.yaml")

    fun docWithOperandNotes(
        sourceOp: OperandInfo? = operandWithNotes,
        targetOp: OperandInfo? = emptyOperand,
        validation: CompareValidation? = null,
    ) = SchemaCompareDocument(
        status = "identical",
        exitCode = 0,
        source = "/tmp/a.yaml",
        target = "/tmp/b.yaml",
        summary = SchemaCompareSummary(),
        diff = null,
        validation = validation,
        sourceOperand = sourceOp,
        targetOperand = targetOp,
    )

    // ── JSON: operand notes visibility ──────────────────────────

    test("json renders source_operand with notes and skipped_objects") {
        val json = CompareRendererJson.render(docWithOperandNotes())
        json shouldContain """"source_operand""""
        json shouldContain """"reference": "/tmp/reverse.yaml""""
        json shouldContain """"severity": "warning""""
        json shouldContain """"code": "W116""""
        json shouldContain """"object_name": "dmg_sequences""""
        json shouldContain """"message": "Degraded sequence emulation detected""""
        json shouldContain """"skipped_objects""""
        json shouldContain """"type": "trigger""""
        json shouldContain """"name": "trg_users_id_seq""""
    }

    test("json omits target_operand when it has no notes or skippedObjects") {
        val json = CompareRendererJson.render(docWithOperandNotes())
        json shouldNotContain """"target_operand""""
    }

    test("json renders both operands when both have notes") {
        val doc = docWithOperandNotes(
            sourceOp = operandWithNotes,
            targetOp = operandWithNotes,
        )
        val json = CompareRendererJson.render(doc)
        json shouldContain """"source_operand""""
        json shouldContain """"target_operand""""
    }

    test("json does not place operand notes inside validation") {
        val doc = docWithOperandNotes(
            validation = CompareValidation(
                source = ValidationResult(errors = listOf(
                    ValidationError("E001", "no columns", "tables.t"))),
            ),
        )
        val json = CompareRendererJson.render(doc)
        // validation section exists with E001
        json shouldContain """"validation""""
        json shouldContain """"E001""""
        // W116 appears only in source_operand, not in validation
        json shouldContain """"source_operand""""
        json shouldContain """"W116""""
    }

    test("json omits operand sections when no notes and no skipped objects") {
        val doc = docWithOperandNotes(sourceOp = emptyOperand, targetOp = emptyOperand)
        val json = CompareRendererJson.render(doc)
        json shouldNotContain """"source_operand""""
        json shouldNotContain """"target_operand""""
    }

    // ── YAML: operand notes visibility ──────────────────────────

    test("yaml renders source_operand with notes and skipped_objects") {
        val yaml = CompareRendererYaml.render(docWithOperandNotes())
        yaml shouldContain "source_operand:"
        yaml shouldContain """reference: "/tmp/reverse.yaml""""
        yaml shouldContain "severity: warning"
        yaml shouldContain """code: "W116""""
        yaml shouldContain """object_name: "dmg_sequences""""
        yaml shouldContain "skipped_objects:"
        yaml shouldContain """type: "trigger""""
        yaml shouldContain """name: "trg_users_id_seq""""
    }

    test("yaml omits target_operand when it has no notes or skippedObjects") {
        val yaml = CompareRendererYaml.render(docWithOperandNotes())
        yaml shouldNotContain "target_operand:"
    }

    test("yaml renders both operands when both have notes") {
        val doc = docWithOperandNotes(
            sourceOp = operandWithNotes,
            targetOp = operandWithNotes,
        )
        val yaml = CompareRendererYaml.render(doc)
        yaml shouldContain "source_operand:"
        yaml shouldContain "target_operand:"
    }

    test("yaml does not place operand notes inside validation") {
        val doc = docWithOperandNotes(
            validation = CompareValidation(
                source = ValidationResult(errors = listOf(
                    ValidationError("E001", "no columns", "tables.t"))),
            ),
        )
        val yaml = CompareRendererYaml.render(doc)
        yaml shouldContain "validation:"
        yaml shouldContain """"E001""""
        yaml shouldContain "source_operand:"
        yaml shouldContain """"W116""""
    }

    test("yaml omits operand sections when no notes and no skipped objects") {
        val doc = docWithOperandNotes(sourceOp = emptyOperand, targetOp = emptyOperand)
        val yaml = CompareRendererYaml.render(doc)
        yaml shouldNotContain "source_operand:"
        yaml shouldNotContain "target_operand:"
    }

    // ── Plain: regression guard ─────────────────────────────────

    test("plain still renders operand info as before") {
        val plain = CompareRendererPlain.render(
            docWithOperandNotes().copy(status = "different", exitCode = 1,
                diff = DiffView(tablesAdded = listOf(TableSummaryView("t", 1))),
                summary = SchemaCompareSummary(tablesAdded = 1)),
        )
        plain shouldContain "Operand (source)"
        plain shouldContain "W116"
        plain shouldContain "dmg_sequences"
        plain shouldContain "skipped"
        plain shouldContain "trg_users_id_seq"
    }
})
