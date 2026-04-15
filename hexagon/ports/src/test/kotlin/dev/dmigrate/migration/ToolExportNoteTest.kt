package dev.dmigrate.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class ToolExportNoteTest : FunSpec({

    test("all fields populated") {
        val note = ToolExportNote(
            severity = ToolExportSeverity.WARNING,
            code = "TE001",
            message = "Flyway edition does not support placeholders",
            objectName = "users",
            hint = "Use Flyway Teams for placeholder support",
        )
        note.severity shouldBe ToolExportSeverity.WARNING
        note.code shouldBe "TE001"
        note.message shouldBe "Flyway edition does not support placeholders"
        note.objectName shouldBe "users"
        note.hint shouldBe "Use Flyway Teams for placeholder support"
    }

    test("optional fields default to null") {
        val note = ToolExportNote(
            severity = ToolExportSeverity.INFO,
            code = "TE002",
            message = "Export completed",
        )
        note.objectName shouldBe null
        note.hint shouldBe null
    }

    test("with objectName only") {
        val note = ToolExportNote(
            severity = ToolExportSeverity.ACTION_REQUIRED,
            code = "TE003",
            message = "Manual migration required",
            objectName = "legacy_data",
        )
        note.objectName shouldBe "legacy_data"
        note.hint shouldBe null
    }

    test("with hint only") {
        val note = ToolExportNote(
            severity = ToolExportSeverity.INFO,
            code = "TE004",
            message = "Consider adding index",
            hint = "CREATE INDEX idx_email ON users(email)",
        )
        note.objectName shouldBe null
        note.hint shouldBe "CREATE INDEX idx_email ON users(email)"
    }

    test("equality based on all fields") {
        val a = ToolExportNote(ToolExportSeverity.WARNING, "TE001", "msg", "obj", "hint")
        val b = ToolExportNote(ToolExportSeverity.WARNING, "TE001", "msg", "obj", "hint")
        a shouldBe b
    }

    test("different severity breaks equality") {
        val a = ToolExportNote(ToolExportSeverity.INFO, "TE001", "msg")
        val b = ToolExportNote(ToolExportSeverity.WARNING, "TE001", "msg")
        a shouldNotBe b
    }

    test("different code breaks equality") {
        val a = ToolExportNote(ToolExportSeverity.INFO, "TE001", "msg")
        val b = ToolExportNote(ToolExportSeverity.INFO, "TE002", "msg")
        a shouldNotBe b
    }

    test("all severity levels are distinct") {
        val severities = ToolExportSeverity.entries
        severities.size shouldBe 3
        severities.toSet().size shouldBe 3
    }

    test("ToolExportResult carries export notes separate from bundle") {
        val notes = listOf(
            ToolExportNote(ToolExportSeverity.INFO, "TE010", "info note"),
            ToolExportNote(ToolExportSeverity.WARNING, "TE011", "warning note", objectName = "orders"),
        )
        val result = ToolExportResult(
            artifacts = listOf(
                MigrationArtifact(ArtifactRelativePath.of("V1__init.sql"), "up", "CREATE TABLE t;"),
            ),
            exportNotes = notes,
        )
        result.exportNotes shouldBe notes
        result.artifacts.size shouldBe 1
    }

    test("ToolExportResult defaults to empty export notes") {
        val result = ToolExportResult(
            artifacts = listOf(
                MigrationArtifact(ArtifactRelativePath.of("V1__init.sql"), "up", "CREATE TABLE t;"),
            ),
        )
        result.exportNotes shouldBe emptyList()
    }
})
