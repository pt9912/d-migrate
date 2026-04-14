package dev.dmigrate.format.report

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import kotlin.io.path.readText

class ReverseReportWriterTest : FunSpec({

    val writer = ReverseReportWriter()

    fun input(
        sourceKind: ReverseSourceKind = ReverseSourceKind.ALIAS,
        sourceValue: String = "production",
        notes: List<SchemaReadNote> = emptyList(),
        skipped: List<SkippedObject> = emptyList(),
    ) = SchemaReadReportInput(
        source = ReverseSourceRef(sourceKind, sourceValue),
        result = SchemaReadResult(
            schema = SchemaDefinition(name = "TestDB", version = "1.0"),
            notes = notes,
            skippedObjects = skipped,
        ),
    )

    test("report with alias source shows value unmasked") {
        val report = writer.render(input(
            sourceKind = ReverseSourceKind.ALIAS,
            sourceValue = "production",
        ))
        report shouldContain "kind: alias"
        report shouldContain "value: \"production\""
    }

    test("report with URL source renders pre-scrubbed value") {
        // Caller is responsible for scrubbing via LogScrubber.maskUrl()
        val report = writer.render(input(
            sourceKind = ReverseSourceKind.URL,
            sourceValue = "postgresql://admin:***@localhost/mydb",
        ))
        report shouldContain "kind: url"
        report shouldContain "postgresql://admin:***@localhost/mydb"
        report shouldNotContain "secret"
    }

    test("report with URL without password renders as-is") {
        val report = writer.render(input(
            sourceKind = ReverseSourceKind.URL,
            sourceValue = "sqlite:///tmp/test.db",
        ))
        report shouldContain "sqlite:///tmp/test.db"
    }

    test("report contains schema metadata") {
        val report = writer.render(input())
        report shouldContain "schema:"
        report shouldContain "name: \"TestDB\""
        report shouldContain "version: \"1.0\""
    }

    test("report contains correct summary counts") {
        val report = writer.render(input(
            notes = listOf(
                SchemaReadNote(SchemaReadSeverity.INFO, "R001", "t1", "info note"),
                SchemaReadNote(SchemaReadSeverity.WARNING, "R002", "t2", "warning note"),
                SchemaReadNote(SchemaReadSeverity.WARNING, "R003", "t3", "another warning"),
                SchemaReadNote(SchemaReadSeverity.ACTION_REQUIRED, "R100", "t4", "action"),
            ),
            skipped = listOf(
                SkippedObject("TABLE", "sys_table", "System table"),
            ),
        ))
        report shouldContain "notes: 4"
        report shouldContain "warnings: 2"
        report shouldContain "action_required: 1"
        report shouldContain "skipped_objects: 1"
    }

    test("report contains notes with all fields") {
        val report = writer.render(input(
            notes = listOf(
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "R001",
                    objectName = "spatial_ref_sys",
                    message = "PostGIS system table skipped",
                    hint = "Enable extension tracking",
                ),
            ),
        ))
        report shouldContain "severity: warning"
        report shouldContain "code: R001"
        report shouldContain "object: \"spatial_ref_sys\""
        report shouldContain "message: \"PostGIS system table skipped\""
        report shouldContain "hint: \"Enable extension tracking\""
    }

    test("report contains skipped objects with code and hint") {
        val report = writer.render(input(
            skipped = listOf(
                SkippedObject(
                    type = "TABLE",
                    name = "sqlite_stat1",
                    reason = "SQLite system table",
                    code = "S001",
                    hint = "These tables are internal",
                ),
            ),
        ))
        report shouldContain "type: TABLE"
        report shouldContain "name: \"sqlite_stat1\""
        report shouldContain "reason: \"SQLite system table\""
        report shouldContain "code: S001"
        report shouldContain "hint: \"These tables are internal\""
    }

    test("empty notes and skipped_objects sections are omitted as top-level lists") {
        val report = writer.render(input())
        // Summary should still show zeros
        report shouldContain "notes: 0"
        report shouldContain "skipped_objects: 0"
        // Top-level note/skipped list sections should not appear
        val lines = report.lines()
        val topKeys = lines.filter { it.matches(Regex("^[a-z].*:.*")) }.map { it.substringBefore(":") }
        topKeys shouldBe listOf("source", "schema", "summary")
    }

    test("report is valid YAML (re-parseable)") {
        val report = writer.render(input(
            notes = listOf(
                SchemaReadNote(SchemaReadSeverity.WARNING, "R001", "t1", "test"),
            ),
            skipped = listOf(
                SkippedObject("VIEW", "v1", "Unsupported"),
            ),
        ))
        // Basic structural YAML check — starts with source: and contains
        // expected top-level keys
        report shouldContain "source:"
        report shouldContain "schema:"
        report shouldContain "summary:"
        val lines = report.lines()
        val topKeys = lines.filter { it.matches(Regex("^[a-z].*:.*")) }.map { it.substringBefore(":") }
        topKeys shouldBe listOf("source", "schema", "summary", "notes", "skipped_objects")
    }

    // ── E2E: write to file and verify ───────────

    test("E2E: write report to file and verify content") {
        val dir = Files.createTempDirectory("reverse-report-test")
        val reportFile = dir.resolve("schema.report.yaml")
        try {
            val reportInput = input(
                sourceKind = ReverseSourceKind.URL,
                sourceValue = "postgresql://admin:***@db.example.com/production",
                notes = listOf(
                    SchemaReadNote(SchemaReadSeverity.WARNING, "R010", "pg_catalog", "System schema skipped"),
                    SchemaReadNote(SchemaReadSeverity.ACTION_REQUIRED, "R020", "custom_func", "Unknown return type"),
                ),
                skipped = listOf(
                    SkippedObject("TABLE", "spatial_ref_sys", "PostGIS system table", code = "S010"),
                ),
            )

            writer.write(reportFile, reportInput)

            val content = reportFile.readText()

            // Source is pre-scrubbed
            content shouldContain "postgresql://admin:***@db.example.com/production"
            content shouldNotContain "secret"

            // Schema metadata
            content shouldContain "name: \"TestDB\""

            // Summary counts
            content shouldContain "notes: 2"
            content shouldContain "warnings: 1"
            content shouldContain "action_required: 1"
            content shouldContain "skipped_objects: 1"

            // Notes
            content shouldContain "code: R010"
            content shouldContain "code: R020"

            // Skipped objects
            content shouldContain "name: \"spatial_ref_sys\""
            content shouldContain "code: S010"
        } finally {
            Files.deleteIfExists(reportFile)
            Files.deleteIfExists(dir)
        }
    }
})
