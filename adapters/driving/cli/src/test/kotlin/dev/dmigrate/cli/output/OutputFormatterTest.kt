package dev.dmigrate.cli.output

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Direkte Unit-Tests für [OutputFormatter]. Bisher war die Klasse nur
 * transitiv via `CliTest` abgedeckt — hier prüfen wir die JSON-/YAML-/Plain-
 * Formatierung, Error-Ausgabe und Context-Flags (quiet/verbose/no-color)
 * ohne einen CLI-Roundtrip auszuführen.
 */
class OutputFormatterTest : FunSpec({

    /**
     * Kapselt stdout + stderr einer [block]-Ausführung und liefert beide
     * als Strings zurück. Restauriert die originalen Streams immer, auch
     * bei Exceptions.
     */
    fun captureStreams(block: () -> Unit): Pair<String, String> {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
        return out.toString(Charsets.UTF_8) to err.toString(Charsets.UTF_8)
    }

    val sampleSchema = SchemaDefinition(name = "TestSchema", version = "1.2.3")
    val validResult = ValidationResult()
    val warningResult = ValidationResult(
        warnings = listOf(
            ValidationWarning("W100", "name should be snake_case", "tables.Users"),
        )
    )
    val invalidResult = ValidationResult(
        errors = listOf(
            ValidationError("E001", "missing primary key", "tables.users"),
        ),
        warnings = listOf(
            ValidationWarning("W100", "naming style", "tables.users"),
        ),
    )

    // ─── printValidationResult: plain ────────────────────────────

    context("plain output") {

        test("prints schema header, counts, and success marker for a valid schema") {
            val (stdout, stderr) = captureStreams {
                OutputFormatter(CliContext()).printValidationResult(
                    validResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "Validating schema 'TestSchema' v1.2.3"
            stdout shouldContain "Tables:"
            stdout shouldContain "Columns:"
            stdout shouldContain "Indices:"
            stdout shouldContain "Constraints:"
            stdout shouldContain "Validation passed"
            stderr shouldNotContain "Error"
        }

        test("suppresses the header + summary lines in --quiet mode") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(quiet = true)).printValidationResult(
                    validResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldNotContain "Validating schema"
            stdout shouldNotContain "Tables:"
            stdout shouldNotContain "Validation passed"
        }

        test("prints warnings to stderr with their code and message") {
            val (_, stderr) = captureStreams {
                OutputFormatter(CliContext()).printValidationResult(
                    warningResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stderr shouldContain "W100"
            stderr shouldContain "name should be snake_case"
            stderr shouldContain "tables.Users"
        }

        test("prints errors to stderr with their code and message") {
            val (_, stderr) = captureStreams {
                OutputFormatter(CliContext()).printValidationResult(
                    invalidResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stderr shouldContain "E001"
            stderr shouldContain "missing primary key"
            stderr shouldContain "tables.users"
        }

        test("summary says 'Validation failed' for invalid results") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext()).printValidationResult(
                    invalidResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "Validation failed"
            stdout shouldContain "1 error"
            stdout shouldContain "1 warning"
        }

        test("--quiet mode still emits the warning/error lines on stderr (without object path)") {
            val (stdout, stderr) = captureStreams {
                OutputFormatter(CliContext(quiet = true)).printValidationResult(
                    invalidResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldNotContain "Validation failed"
            // quiet still shows the error line itself but NOT the "→ objectPath" detail
            stderr shouldContain "E001"
            stderr shouldNotContain "→ tables.users"
        }
    }

    // ─── printValidationResult: JSON ──────────────────────────────

    context("json output") {

        test("envelope contains command, status, exit_code for valid schema") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "json")).printValidationResult(
                    validResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "\"command\": \"schema.validate\""
            stdout shouldContain "\"status\": \"passed\""
            stdout shouldContain "\"exit_code\": 0"
            stdout shouldContain "\"schema\": {\"name\": \"TestSchema\", \"version\": \"1.2.3\"}"
            stdout shouldContain "\"errors\": 0"
            stdout shouldContain "\"warnings\": 0"
            stdout shouldContain "\"results\": []"
        }

        test("failed status and exit_code 3 for invalid schema") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "json")).printValidationResult(
                    invalidResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "\"status\": \"failed\""
            stdout shouldContain "\"exit_code\": 3"
            stdout shouldContain "\"errors\": 1"
            stdout shouldContain "\"warnings\": 1"
        }

        test("results array contains warning and error entries with levels") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "json")).printValidationResult(
                    invalidResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "\"level\": \"warning\""
            stdout shouldContain "\"level\": \"error\""
            stdout shouldContain "\"code\": \"E001\""
            stdout shouldContain "\"code\": \"W100\""
        }

        test("escapes special characters in code/object/message") {
            val tricky = ValidationResult(
                errors = listOf(
                    ValidationError(
                        "E999",
                        "message with \"quotes\" and \\ backslash\nand newline",
                        "path/with\tspecial",
                    )
                )
            )
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "json")).printValidationResult(
                    tricky,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            // Escaped forms must be present; raw control chars must not
            stdout shouldContain "\\\""
            stdout shouldContain "\\\\"
            stdout shouldContain "\\n"
            stdout shouldContain "\\t"
        }
    }

    // ─── printValidationResult: YAML ──────────────────────────────

    context("yaml output") {

        test("envelope contains command, status, exit_code for valid schema") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "yaml")).printValidationResult(
                    validResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "command: schema.validate"
            stdout shouldContain "status: passed"
            stdout shouldContain "exit_code: 0"
            stdout shouldContain "errors: 0"
            stdout shouldContain "warnings: 0"
            stdout shouldContain "results: []"
        }

        test("failed status and exit_code 3 for invalid schema, with entries") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "yaml")).printValidationResult(
                    invalidResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "status: failed"
            stdout shouldContain "exit_code: 3"
            stdout shouldContain "errors: 1"
            stdout shouldContain "warnings: 1"
            stdout shouldContain "level: warning"
            stdout shouldContain "level: error"
            stdout shouldContain "code: E001"
            stdout shouldContain "code: W100"
        }

        test("schema block has name and version") {
            val (stdout, _) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "yaml")).printValidationResult(
                    validResult,
                    sampleSchema,
                    "/tmp/schema.yaml",
                )
            }
            stdout shouldContain "name: \"TestSchema\""
            stdout shouldContain "version: \"1.2.3\""
        }
    }

    // ─── printError ───────────────────────────────────────────────

    context("printError") {

        test("plain format prints [ERROR] prefix and file arrow on stderr") {
            val (_, stderr) = captureStreams {
                OutputFormatter(CliContext()).printError("oops", "/tmp/bad.yaml")
            }
            stderr shouldContain "[ERROR] oops"
            stderr shouldContain "→ File: /tmp/bad.yaml"
        }

        test("json format prints a JSON object with error+file keys on stderr") {
            val (_, stderr) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "json")).printError("oops", "/tmp/bad.yaml")
            }
            stderr shouldContain "\"error\": \"oops\""
            stderr shouldContain "\"file\": \"/tmp/bad.yaml\""
        }

        test("yaml format prints error: and file: keys on stderr") {
            val (_, stderr) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "yaml")).printError("oops", "/tmp/bad.yaml")
            }
            stderr shouldContain "error: \"oops\""
            stderr shouldContain "file: \"/tmp/bad.yaml\""
        }

        test("json format escapes quotes/backslashes/newlines in message and file") {
            val (_, stderr) = captureStreams {
                OutputFormatter(CliContext(outputFormat = "json")).printError(
                    "with \"quote\" and \\ backslash\nand newline",
                    "weird\tpath",
                )
            }
            stderr shouldContain "\\\""
            stderr shouldContain "\\\\"
            stderr shouldContain "\\n"
            stderr shouldContain "\\t"
        }
    }
})
