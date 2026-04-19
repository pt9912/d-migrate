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

    // ── DdlPhase model (0.9.2 AP 6.1) ──────────────────────────────

    test("DdlPhase has PRE_DATA and POST_DATA") {
        DdlPhase.entries.map { it.name } shouldBe listOf("PRE_DATA", "POST_DATA")
    }

    test("DdlStatement phase defaults to PRE_DATA") {
        val stmt = DdlStatement("CREATE TABLE t (id INT);")
        stmt.phase shouldBe DdlPhase.PRE_DATA
    }

    test("TransformationNote phase defaults to null") {
        val note = TransformationNote(NoteType.INFO, "I001", "x", "info")
        note.phase shouldBe null
    }

    test("SkippedObject phase defaults to null") {
        val skip = SkippedObject("table", "t", "not supported")
        skip.phase shouldBe null
    }

    test("DdlResult render unchanged with phase-tagged statements") {
        val result = DdlResult(
            statements = listOf(
                DdlStatement("CREATE TABLE a (id INT);", phase = DdlPhase.PRE_DATA),
                DdlStatement("CREATE TRIGGER trg;", phase = DdlPhase.POST_DATA),
            ),
        )
        result.render() shouldBe "CREATE TABLE a (id INT);\n\nCREATE TRIGGER trg;"
    }

    test("statementsForPhase filters by phase") {
        val pre = DdlStatement("CREATE TABLE a;", phase = DdlPhase.PRE_DATA)
        val post = DdlStatement("CREATE TRIGGER trg;", phase = DdlPhase.POST_DATA)
        val result = DdlResult(statements = listOf(pre, post))
        result.statementsForPhase(DdlPhase.PRE_DATA) shouldBe listOf(pre)
        result.statementsForPhase(DdlPhase.POST_DATA) shouldBe listOf(post)
    }

    test("renderPhase renders only matching statements") {
        val result = DdlResult(
            statements = listOf(
                DdlStatement("CREATE TABLE a;", phase = DdlPhase.PRE_DATA),
                DdlStatement("CREATE TABLE b;", phase = DdlPhase.PRE_DATA),
                DdlStatement("CREATE TRIGGER trg;", phase = DdlPhase.POST_DATA),
            ),
        )
        result.renderPhase(DdlPhase.PRE_DATA) shouldBe "CREATE TABLE a;\n\nCREATE TABLE b;"
        result.renderPhase(DdlPhase.POST_DATA) shouldBe "CREATE TRIGGER trg;"
    }

    test("renderPhase for empty phase yields empty string") {
        val result = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE a;", phase = DdlPhase.PRE_DATA)),
        )
        result.renderPhase(DdlPhase.POST_DATA) shouldBe ""
    }

    test("notes aggregates statement notes plus globalNotes") {
        val stmtNote = TransformationNote(NoteType.INFO, "I001", "a", "stmt note")
        val globalNote = TransformationNote(NoteType.WARNING, "W001", "b", "global note")
        val result = DdlResult(
            statements = listOf(DdlStatement("SELECT 1;", listOf(stmtNote))),
            globalNotes = listOf(globalNote),
        )
        result.notes shouldBe listOf(stmtNote, globalNote)
    }

    test("notesForPhase returns statement notes via statement phase") {
        val preNote = TransformationNote(NoteType.INFO, "I001", "a", "pre note")
        val postNote = TransformationNote(NoteType.WARNING, "W001", "b", "post note")
        val result = DdlResult(
            statements = listOf(
                DdlStatement("CREATE TABLE a;", listOf(preNote), DdlPhase.PRE_DATA),
                DdlStatement("CREATE TRIGGER trg;", listOf(postNote), DdlPhase.POST_DATA),
            ),
        )
        result.notesForPhase(DdlPhase.PRE_DATA) shouldBe listOf(preNote)
        result.notesForPhase(DdlPhase.POST_DATA) shouldBe listOf(postNote)
    }

    test("notesForPhase ignores note.phase on statement-bound notes") {
        // note.phase says POST_DATA but the statement is PRE_DATA → statement wins
        val note = TransformationNote(NoteType.INFO, "I001", "a", "note", phase = DdlPhase.POST_DATA)
        val result = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE a;", listOf(note), DdlPhase.PRE_DATA)),
        )
        result.notesForPhase(DdlPhase.PRE_DATA) shouldBe listOf(note)
        result.notesForPhase(DdlPhase.POST_DATA) shouldBe emptyList()
    }

    test("notesForPhase includes phase-tagged globalNotes") {
        val globalPre = TransformationNote(NoteType.INFO, "I001", "g", "global pre", phase = DdlPhase.PRE_DATA)
        val globalPost = TransformationNote(NoteType.WARNING, "W001", "g", "global post", phase = DdlPhase.POST_DATA)
        val globalNull = TransformationNote(NoteType.INFO, "I002", "g", "global null", phase = null)
        val result = DdlResult(
            statements = emptyList(),
            globalNotes = listOf(globalPre, globalPost, globalNull),
        )
        result.notesForPhase(DdlPhase.PRE_DATA) shouldBe listOf(globalPre)
        result.notesForPhase(DdlPhase.POST_DATA) shouldBe listOf(globalPost)
    }

    test("notesForPhase: globalNotes with null phase do not appear in phase view") {
        val nullNote = TransformationNote(NoteType.INFO, "I001", "x", "unphased", phase = null)
        val result = DdlResult(statements = emptyList(), globalNotes = listOf(nullNote))
        result.notesForPhase(DdlPhase.PRE_DATA) shouldBe emptyList()
        result.notesForPhase(DdlPhase.POST_DATA) shouldBe emptyList()
    }

    test("render and renderPhase do not include globalNotes") {
        val globalNote = TransformationNote(NoteType.WARNING, "W001", "g", "global", phase = DdlPhase.PRE_DATA)
        val result = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE a;", phase = DdlPhase.PRE_DATA)),
            globalNotes = listOf(globalNote),
        )
        result.render() shouldBe "CREATE TABLE a;"
        result.renderPhase(DdlPhase.PRE_DATA) shouldBe "CREATE TABLE a;"
    }

    test("skippedObjectsForPhase filters by explicit phase") {
        val prePre = SkippedObject("table", "a", "not supported", phase = DdlPhase.PRE_DATA)
        val postPost = SkippedObject("trigger", "trg", "no body", phase = DdlPhase.POST_DATA)
        val nullPhase = SkippedObject("view", "v", "skipped", phase = null)
        val result = DdlResult(
            statements = emptyList(),
            skippedObjects = listOf(prePre, postPost, nullPhase),
        )
        result.skippedObjectsForPhase(DdlPhase.PRE_DATA) shouldBe listOf(prePre)
        result.skippedObjectsForPhase(DdlPhase.POST_DATA) shouldBe listOf(postPost)
    }

    test("skippedObjectsForPhase does not return null-phase objects") {
        val nullPhase = SkippedObject("view", "v", "skipped", phase = null)
        val result = DdlResult(statements = emptyList(), skippedObjects = listOf(nullPhase))
        result.skippedObjectsForPhase(DdlPhase.PRE_DATA) shouldBe emptyList()
        result.skippedObjectsForPhase(DdlPhase.POST_DATA) shouldBe emptyList()
    }

    test("skippedObjectsForPhase preserves original order") {
        val s1 = SkippedObject("a", "a1", "r", phase = DdlPhase.POST_DATA)
        val s2 = SkippedObject("b", "b1", "r", phase = DdlPhase.PRE_DATA)
        val s3 = SkippedObject("c", "c1", "r", phase = DdlPhase.POST_DATA)
        val result = DdlResult(statements = emptyList(), skippedObjects = listOf(s1, s2, s3))
        result.skippedObjectsForPhase(DdlPhase.POST_DATA) shouldBe listOf(s1, s3)
    }

    test("renderPhase preserves relative order from statements") {
        val result = DdlResult(
            statements = listOf(
                DdlStatement("A;", phase = DdlPhase.PRE_DATA),
                DdlStatement("B;", phase = DdlPhase.POST_DATA),
                DdlStatement("C;", phase = DdlPhase.PRE_DATA),
                DdlStatement("D;", phase = DdlPhase.POST_DATA),
            ),
        )
        result.renderPhase(DdlPhase.PRE_DATA) shouldBe "A;\n\nC;"
        result.renderPhase(DdlPhase.POST_DATA) shouldBe "B;\n\nD;"
    }

    test("ManualActionRequired toNote with phase") {
        val action = ManualActionRequired("E053", "trigger", "trg1", "no body")
        val note = action.toNote(DdlPhase.POST_DATA)
        note.phase shouldBe DdlPhase.POST_DATA
        note.type shouldBe NoteType.ACTION_REQUIRED
    }

    test("ManualActionRequired toSkipped with phase") {
        val action = ManualActionRequired("E053", "trigger", "trg1", "no body")
        val skipped = action.toSkipped(DdlPhase.POST_DATA)
        skipped.phase shouldBe DdlPhase.POST_DATA
    }

    test("ManualActionRequired toNote without phase defaults to null") {
        val action = ManualActionRequired("E053", "function", "fn1", "no body")
        action.toNote().phase shouldBe null
    }

    test("ManualActionRequired toSkipped without phase defaults to null") {
        val action = ManualActionRequired("E053", "function", "fn1", "no body")
        action.toSkipped().phase shouldBe null
    }

    test("mixed statement and global notes in notesForPhase with correct order") {
        val stmtNote1 = TransformationNote(NoteType.INFO, "I001", "a", "from stmt 1")
        val stmtNote2 = TransformationNote(NoteType.INFO, "I002", "b", "from stmt 2")
        val globalPre = TransformationNote(NoteType.WARNING, "W001", "g", "global pre", phase = DdlPhase.PRE_DATA)
        val result = DdlResult(
            statements = listOf(
                DdlStatement("S1;", listOf(stmtNote1), DdlPhase.PRE_DATA),
                DdlStatement("S2;", listOf(stmtNote2), DdlPhase.POST_DATA),
            ),
            globalNotes = listOf(globalPre),
        )
        result.notesForPhase(DdlPhase.PRE_DATA) shouldBe listOf(stmtNote1, globalPre)
        result.notesForPhase(DdlPhase.POST_DATA) shouldBe listOf(stmtNote2)
    }
})
