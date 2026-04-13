package dev.dmigrate.format.report

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

class TransformationReportWriterTest : FunSpec({

    val writer = TransformationReportWriter()

    fun schema(name: String = "Test", version: String = "1.0") =
        SchemaDefinition(name = name, version = version)

    test("report contains source and target info") {
        val result = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE t (id INT);")),
            skippedObjects = emptyList()
        )
        val report = writer.render(result, schema("MyApp", "2.0"), "postgresql", Path.of("schema.yaml"))
        report shouldContain "schema: \"MyApp\""
        report shouldContain "version: \"2.0\""
        report shouldContain "file: \"schema.yaml\""
        report shouldContain "dialect: postgresql"
        report shouldContain "generator: \"d-migrate 0.2.0\""
    }

    test("report contains summary counts") {
        val result = DdlResult(
            statements = listOf(
                DdlStatement("CREATE TABLE t1;"),
                DdlStatement("CREATE TABLE t2;", listOf(
                    TransformationNote(NoteType.WARNING, "W100", "t2.col", "tz lost")
                )),
                DdlStatement("-- skipped", listOf(
                    TransformationNote(NoteType.ACTION_REQUIRED, "E052", "fn", "needs KI")
                ))
            ),
            skippedObjects = listOf(SkippedObject("function", "calc", "wrong dialect"))
        )
        val report = writer.render(result, schema(), "mysql", Path.of("test.yaml"))
        report shouldContain "statements: 3"
        report shouldContain "notes: 2"
        report shouldContain "warnings: 1"
        report shouldContain "action_required: 1"
        report shouldContain "skipped_objects: 1"
    }

    test("report lists all notes") {
        val result = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE t;", listOf(
                TransformationNote(NoteType.WARNING, "W102", "idx", "hash not supported", "Use BTREE"),
                TransformationNote(NoteType.INFO, "I001", "type", "enum mapped inline")
            ))),
        )
        val report = writer.render(result, schema(), "sqlite", Path.of("s.yaml"))
        report shouldContain "code: W102"
        report shouldContain "object: \"idx\""
        report shouldContain "message: \"hash not supported\""
        report shouldContain "hint: \"Use BTREE\""
        report shouldContain "code: I001"
    }

    test("report lists skipped objects") {
        val result = DdlResult(
            statements = emptyList(),
            skippedObjects = listOf(
                SkippedObject("function", "calc_total", "requires KI transformation"),
                SkippedObject("trigger", "trg_audit", "wrong dialect")
            )
        )
        val report = writer.render(result, schema(), "mysql", Path.of("s.yaml"))
        report shouldContain "name: \"calc_total\""
        report shouldContain "reason: \"requires KI transformation\""
        report shouldContain "name: \"trg_audit\""
    }

    test("report with no notes has zero count and no notes list") {
        val result = DdlResult(statements = listOf(DdlStatement("CREATE TABLE t;")))
        val report = writer.render(result, schema(), "postgresql", Path.of("s.yaml"))
        report shouldContain "notes: 0"
        // No "- type:" entries (the notes list section is omitted)
        report shouldNotContain "- type:"
    }

    test("report with no skipped objects has zero count and no skipped list") {
        val result = DdlResult(statements = listOf(DdlStatement("CREATE TABLE t;")))
        val report = writer.render(result, schema(), "postgresql", Path.of("s.yaml"))
        report shouldContain "skipped_objects: 0"
        // No "- name:" entries (the skipped_objects list section is omitted)
        report shouldNotContain "- name:"
    }

    test("report escapes special characters in strings") {
        val result = DdlResult(
            statements = listOf(DdlStatement("t", listOf(
                TransformationNote(NoteType.WARNING, "W1", "obj", "has \"quotes\" and\nnewlines")
            )))
        )
        val report = writer.render(result, schema("App \"Test\""), "pg", Path.of("s.yaml"))
        report shouldContain "\\\"quotes\\\""
        report shouldContain "\\n"
    }

    test("report includes SkippedObject code and hint") {
        val result = DdlResult(
            statements = emptyList(),
            skippedObjects = listOf(SkippedObject(
                type = "table", name = "geo_table", reason = "No spatial profile",
                code = "E052", hint = "Enable postgis"
            ))
        )
        val report = writer.render(result, schema(), "postgresql", Path.of("test.yaml"))
        report shouldContain "code: E052"
        report shouldContain "hint: \"Enable postgis\""
        report shouldContain "action_required: 1"
    }
})
