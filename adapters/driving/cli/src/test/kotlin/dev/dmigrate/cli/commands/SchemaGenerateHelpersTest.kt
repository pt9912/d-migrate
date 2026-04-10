package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

/**
 * Unit-Tests für [SchemaGenerateHelpers] — die reinen Helfer-Funktionen, die
 * [SchemaGenerateCommand] aus seiner `run()`-Methode aufruft.
 *
 * Alle Tests laufen ohne Clikt-Kontext, ohne Dateisystem-I/O und ohne
 * Schema-Codec — sie decken die Pfad-Berechnung, JSON-Serialisierung und
 * Dialect→Generator-Auflösung ab.
 */
class SchemaGenerateHelpersTest : FunSpec({

    // ─── sidecarPath ──────────────────────────────────────────────

    context("sidecarPath") {
        test("replaces extension on a file with a dot in the name") {
            SchemaGenerateHelpers.sidecarPath(Path.of("/tmp/schema.sql"), ".report.yaml") shouldBe
                Path.of("/tmp/schema.report.yaml")
        }

        test("appends suffix when the file has no extension") {
            SchemaGenerateHelpers.sidecarPath(Path.of("/tmp/schema"), ".report.yaml") shouldBe
                Path.of("/tmp/schema.report.yaml")
        }

        test("preserves the parent directory") {
            SchemaGenerateHelpers.sidecarPath(Path.of("/a/b/c/schema.sql"), ".report.yaml") shouldBe
                Path.of("/a/b/c/schema.report.yaml")
        }

        test("relative path with no parent → relative sidecar") {
            SchemaGenerateHelpers.sidecarPath(Path.of("schema.sql"), ".report.yaml") shouldBe
                Path.of("schema.report.yaml")
        }

        test("handles leading-dot files (dotIndex == 0) by appending, not replacing") {
            // `.hidden` has dotIndex 0, so dotIndex > 0 is false → suffix appended
            SchemaGenerateHelpers.sidecarPath(Path.of(".hidden"), ".report.yaml") shouldBe
                Path.of(".hidden.report.yaml")
        }

        test("handles multi-dot filenames by stripping only the last extension") {
            SchemaGenerateHelpers.sidecarPath(Path.of("/tmp/schema.v2.sql"), ".report.yaml") shouldBe
                Path.of("/tmp/schema.v2.report.yaml")
        }

        test("custom suffix is passed through verbatim") {
            SchemaGenerateHelpers.sidecarPath(Path.of("/tmp/out.sql"), ".custom.json") shouldBe
                Path.of("/tmp/out.custom.json")
        }
    }

    // ─── rollbackPath ─────────────────────────────────────────────

    context("rollbackPath") {
        test("inserts '.rollback' before the extension") {
            SchemaGenerateHelpers.rollbackPath(Path.of("/tmp/schema.sql")) shouldBe
                Path.of("/tmp/schema.rollback.sql")
        }

        test("appends '.rollback' when the file has no extension") {
            SchemaGenerateHelpers.rollbackPath(Path.of("/tmp/schema")) shouldBe
                Path.of("/tmp/schema.rollback")
        }

        test("preserves parent directory") {
            SchemaGenerateHelpers.rollbackPath(Path.of("/a/b/schema.sql")) shouldBe
                Path.of("/a/b/schema.rollback.sql")
        }

        test("relative path with no parent → relative rollback") {
            SchemaGenerateHelpers.rollbackPath(Path.of("schema.sql")) shouldBe
                Path.of("schema.rollback.sql")
        }

        test("handles multi-dot filenames by inserting before the last extension") {
            SchemaGenerateHelpers.rollbackPath(Path.of("/tmp/schema.v2.sql")) shouldBe
                Path.of("/tmp/schema.v2.rollback.sql")
        }

        test("handles leading-dot file by appending '.rollback'") {
            SchemaGenerateHelpers.rollbackPath(Path.of(".hidden")) shouldBe
                Path.of(".hidden.rollback")
        }
    }

    // ─── formatJsonOutput ─────────────────────────────────────────

    context("formatJsonOutput") {

        fun schema(name: String = "TestApp", version: String = "1.0.0") =
            SchemaDefinition(name = name, version = version)

        fun result(
            statements: List<DdlStatement> = listOf(DdlStatement("CREATE TABLE users (id INT)")),
            skippedObjects: List<SkippedObject> = emptyList(),
        ) = DdlResult(statements = statements, skippedObjects = skippedObjects)

        test("produces the expected top-level envelope") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(),
                schema(),
                "postgresql",
            )
            json shouldContain "\"command\": \"schema.generate\""
            json shouldContain "\"status\": \"completed\""
            json shouldContain "\"exit_code\": 0"
            json shouldContain "\"target\": \"postgresql\""
            json shouldContain "\"schema\": {\"name\": \"TestApp\", \"version\": \"1.0.0\"}"
        }

        test("includes the rendered DDL as a JSON-escaped string") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(listOf(DdlStatement("CREATE TABLE \"users\" (id INT)"))),
                schema(),
                "postgresql",
            )
            // Double quotes in DDL must be backslash-escaped in JSON
            json shouldContain "\\\"users\\\""
        }

        test("escapes newlines in the DDL output") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(listOf(DdlStatement("CREATE TABLE a (id INT);\nCREATE TABLE b (id INT);"))),
                schema(),
                "mysql",
            )
            json shouldContain "\\n"
            // Raw newline inside a string value would break the JSON format
            json shouldNotContain "INT);\nCREATE"
        }

        test("counts warnings and action_required separately") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(
                    listOf(
                        DdlStatement(
                            "CREATE TABLE t (id INT)",
                            notes = listOf(
                                TransformationNote(NoteType.WARNING, "W001", "t", "hint1"),
                                TransformationNote(NoteType.WARNING, "W002", "t", "hint2"),
                                TransformationNote(NoteType.ACTION_REQUIRED, "A001", "t", "act"),
                                TransformationNote(NoteType.INFO, "I001", "t", "info"),
                            ),
                        )
                    )
                ),
                schema(),
                "postgresql",
            )
            json shouldContain "\"warnings\": 2"
            json shouldContain "\"action_required\": 1"
            // Info notes land in the notes array but not in the counters
            json shouldContain "\"code\": \"I001\""
        }

        test("renders empty notes array when there are no notes") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(),
                schema(),
                "sqlite",
            )
            json shouldContain "\"notes\": []"
            json shouldContain "\"skipped_objects\": []"
        }

        test("renders skipped_objects when present") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(
                    skippedObjects = listOf(
                        SkippedObject("procedure", "legacy_proc", "not supported on mysql"),
                    ),
                ),
                schema(),
                "mysql",
            )
            json shouldContain "\"type\": \"procedure\""
            json shouldContain "\"name\": \"legacy_proc\""
            json shouldContain "\"reason\": \"not supported on mysql\""
            json shouldNotContain "\"skipped_objects\": []"
        }

        test("includes note type (lowercased), code, object and message") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(
                    listOf(
                        DdlStatement(
                            "CREATE VIEW v AS SELECT 1",
                            notes = listOf(
                                TransformationNote(
                                    NoteType.ACTION_REQUIRED,
                                    "T099",
                                    "view.v",
                                    "Function not supported",
                                ),
                            ),
                        )
                    )
                ),
                schema(),
                "postgresql",
            )
            json shouldContain "\"type\": \"action_required\""
            json shouldContain "\"code\": \"T099\""
            json shouldContain "\"object\": \"view.v\""
            json shouldContain "\"message\": \"Function not supported\""
        }

        test("escapes double quotes in schema name and version") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(),
                schema(name = "quote\"test", version = "1.0.0"),
                "postgresql",
            )
            json shouldContain "\"name\": \"quote\\\"test\""
        }
    }

    // ─── escapeJson ───────────────────────────────────────────────

    context("escapeJson") {
        test("escapes backslash") {
            SchemaGenerateHelpers.escapeJson("a\\b") shouldBe "a\\\\b"
        }

        test("escapes double quote") {
            SchemaGenerateHelpers.escapeJson("a\"b") shouldBe "a\\\"b"
        }

        test("escapes newline") {
            SchemaGenerateHelpers.escapeJson("a\nb") shouldBe "a\\nb"
        }

        test("escapes carriage return") {
            SchemaGenerateHelpers.escapeJson("a\rb") shouldBe "a\\rb"
        }

        test("escapes tab") {
            SchemaGenerateHelpers.escapeJson("a\tb") shouldBe "a\\tb"
        }

        test("leaves plain ASCII untouched") {
            SchemaGenerateHelpers.escapeJson("Hello, World 123") shouldBe "Hello, World 123"
        }

        test("handles empty string") {
            SchemaGenerateHelpers.escapeJson("") shouldBe ""
        }

        test("escapes all special characters in a single string") {
            SchemaGenerateHelpers.escapeJson("\\\"\n\r\t") shouldBe "\\\\\\\"\\n\\r\\t"
        }
    }
})
