package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlPhase
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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

        test("JSON output includes SkippedObject code and hint") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(
                    skippedObjects = listOf(
                        SkippedObject(
                            type = "table", name = "places", reason = "Spatial profile is none",
                            code = "E052", hint = "Use --spatial-profile postgis"
                        )
                    )
                ),
                schema(),
                "postgresql",
            )
            json shouldContain "\"code\": \"E052\""
            json shouldContain "\"hint\": \"Use --spatial-profile postgis\""
            json shouldContain "\"action_required\": 1"
        }

        test("JSON output renders spatial W120 notes alongside E052 skipped objects") {
            val json = SchemaGenerateHelpers.formatJsonOutput(
                result(
                    statements = listOf(
                        DdlStatement(
                            "CREATE TABLE places (location POINT /*!80003 SRID 4326 */)",
                            notes = listOf(
                                TransformationNote(
                                    NoteType.WARNING,
                                    "W120",
                                    "places.location",
                                    "SRID 4326 emitted as MySQL comment hint; full SRID constraint support depends on MySQL 8.0+",
                                ),
                            ),
                        )
                    ),
                    skippedObjects = listOf(
                        SkippedObject(
                            type = "table",
                            name = "blocked_places",
                            reason = "Spatial profile is none",
                            code = "E052",
                            hint = "Use --spatial-profile postgis"
                        )
                    )
                ),
                schema(),
                "mysql",
            )

            json shouldContain "\"code\": \"W120\""
            json shouldContain "\"object\": \"places.location\""
            json shouldContain "\"code\": \"E052\""
            json shouldContain "\"name\": \"blocked_places\""
            json shouldContain "\"warnings\": 1"
            json shouldContain "\"action_required\": 1"
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

    // ─── JSON/Report Contract Tests (0.9.2 AP 6.7 Step B) ────────

    val splitTestResult = DdlResult(
        statements = listOf(
            DdlStatement("CREATE TABLE t (id INT);", phase = DdlPhase.PRE_DATA,
                notes = listOf(TransformationNote(NoteType.WARNING, "W100", "t.col", "type mapped"))),
            DdlStatement("CREATE TRIGGER trg;", phase = DdlPhase.POST_DATA),
        ),
        skippedObjects = listOf(
            SkippedObject("sequence", "seq1", "not supported", code = "E056", phase = DdlPhase.PRE_DATA),
        ),
        globalNotes = listOf(
            TransformationNote(NoteType.WARNING, "W113", "views", "circular deps"),
        ),
    )
    val testSchema = SchemaDefinition(name = "Test", version = "1.0.0")

    test("split JSON contains split_mode and ddl_parts") {
        val json = SchemaGenerateHelpers.formatJsonOutput(splitTestResult, testSchema, "postgresql", SplitMode.PRE_POST)
        json shouldContain """"split_mode": "pre-post""""
        json shouldContain """"ddl_parts""""
        json shouldContain """"pre_data""""
        json shouldContain """"post_data""""
    }

    test("split JSON does not contain legacy ddl field") {
        val json = SchemaGenerateHelpers.formatJsonOutput(splitTestResult, testSchema, "postgresql", SplitMode.PRE_POST)
        json.lines().none { it.trimStart().startsWith("\"ddl\":") } shouldBe true
    }

    test("split JSON contains phase on skipped_objects with explicit phase") {
        val json = SchemaGenerateHelpers.formatJsonOutput(splitTestResult, testSchema, "postgresql", SplitMode.PRE_POST)
        // SkippedObject has explicit phase = PRE_DATA
        json shouldContain """"phase": "pre-data""""
    }

    test("split JSON omits phase on notes without explicit phase") {
        // Statement-bound notes have note.phase = null; phase comes from the statement
        // but formatJsonOutput only serializes note.phase, not statement.phase
        val json = SchemaGenerateHelpers.formatJsonOutput(splitTestResult, testSchema, "postgresql", SplitMode.PRE_POST)
        // W100 note has phase = null → no "phase" in its JSON entry
        // W113 globalNote also has phase = null → no "phase"
        // Only the skipped_objects entry has phase = PRE_DATA
        val noteLines = json.lines().filter { it.contains("\"W100\"") || it.contains("\"W113\"") }
        noteLines.all { "phase" !in it || "pre-data" in it || "post-data" in it } shouldBe true
    }

    test("single JSON contains ddl, no split_mode") {
        val json = SchemaGenerateHelpers.formatJsonOutput(splitTestResult, testSchema, "postgresql", SplitMode.SINGLE)
        json shouldContain """"ddl":"""
        json shouldNotContain "split_mode"
        json shouldNotContain "ddl_parts"
    }

    test("single JSON does not contain phase on notes") {
        val json = SchemaGenerateHelpers.formatJsonOutput(splitTestResult, testSchema, "postgresql", SplitMode.SINGLE)
        json shouldNotContain """"phase":"""
    }

    test("splitPath derives pre-data and post-data paths") {
        val base = java.nio.file.Path.of("/tmp/schema.sql")
        SchemaGenerateHelpers.splitPath(base, DdlPhase.PRE_DATA).toString() shouldBe "/tmp/schema.pre-data.sql"
        SchemaGenerateHelpers.splitPath(base, DdlPhase.POST_DATA).toString() shouldBe "/tmp/schema.post-data.sql"
    }

    test("splitPath without extension") {
        val base = java.nio.file.Path.of("/tmp/schema")
        SchemaGenerateHelpers.splitPath(base, DdlPhase.PRE_DATA).toString() shouldBe "/tmp/schema.pre-data"
        SchemaGenerateHelpers.splitPath(base, DdlPhase.POST_DATA).toString() shouldBe "/tmp/schema.post-data"
    }
})
