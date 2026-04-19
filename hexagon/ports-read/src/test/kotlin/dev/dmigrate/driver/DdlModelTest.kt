package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DdlModelTest : FunSpec({

    test("DdlStatement render without notes") {
        val stmt = DdlStatement("CREATE TABLE t (id INT);")
        stmt.render() shouldBe "CREATE TABLE t (id INT);"
    }

    test("DdlStatement render with notes prepends comments") {
        val stmt = DdlStatement(
            "CREATE TABLE t (id INT);",
            listOf(TransformationNote(NoteType.WARNING, "W001", "t", "test warning", "fix it")),
        )
        val rendered = stmt.render()
        rendered shouldContain "-- [W001] test warning"
        rendered shouldContain "-- Hint: fix it"
        rendered shouldContain "CREATE TABLE t (id INT);"
    }

    test("DdlResult render joins statements") {
        val result = DdlResult(
            statements = listOf(
                DdlStatement("CREATE TABLE a (id INT);"),
                DdlStatement("CREATE TABLE b (id INT);"),
            ),
        )
        val rendered = result.render()
        rendered shouldContain "CREATE TABLE a"
        rendered shouldContain "CREATE TABLE b"
    }

    test("DdlResult notes aggregates from statements") {
        val note = TransformationNote(NoteType.INFO, "I001", "x", "info")
        val result = DdlResult(
            statements = listOf(DdlStatement("SELECT 1;", listOf(note))),
        )
        result.notes shouldBe listOf(note)
    }

    test("ManualActionRequired toNote produces ACTION_REQUIRED") {
        val action = ManualActionRequired("E053", "function", "fn1", "no body", "add body")
        val note = action.toNote()
        note.type shouldBe NoteType.ACTION_REQUIRED
        note.code shouldBe "E053"
        note.objectName shouldBe "fn1"
        note.message shouldBe "no body"
        note.hint shouldBe "add body"
    }

    test("ManualActionRequired toSkipped produces SkippedObject") {
        val action = ManualActionRequired("E053", "trigger", "trg1", "wrong dialect")
        val skipped = action.toSkipped()
        skipped.type shouldBe "trigger"
        skipped.name shouldBe "trg1"
        skipped.reason shouldBe "wrong dialect"
        skipped.code shouldBe "E053"
    }

    test("ManualActionRequired toTodoComment renders -- TODO") {
        val action = ManualActionRequired("E053", "function", "fn1", "no body", "add body")
        action.toTodoComment() shouldBe "-- TODO: no body (add body)"
    }

    test("ManualActionRequired toTodoComment without hint") {
        val action = ManualActionRequired("E053", "function", "fn1", "no body")
        action.toTodoComment() shouldBe "-- TODO: no body"
    }

    test("NoteType values") {
        NoteType.entries.map { it.name } shouldBe listOf("INFO", "WARNING", "ACTION_REQUIRED")
    }
})
