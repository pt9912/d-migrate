package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.cli.commands.CompareRendererJson
import dev.dmigrate.cli.commands.CompareRendererYaml
import dev.dmigrate.cli.commands.OperandInfo
import dev.dmigrate.cli.commands.SchemaCompareDocument
import dev.dmigrate.cli.commands.SchemaCompareSummary
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SkippedObject
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

class CliSchemaCompareTest : FunSpec({

    fun resourcePath(name: String): String =
        CliSchemaCompareTest::class.java.getResource("/$name")!!.path

    fun cli() = DMigrate().subcommands(SchemaCommand())

    fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val capture = ByteArrayOutputStream()
        System.setOut(PrintStream(capture, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return capture.toString(Charsets.UTF_8)
    }

    fun captureStderr(block: () -> Unit): String {
        val original = System.err
        val capture = ByteArrayOutputStream()
        System.setErr(PrintStream(capture, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return capture.toString(Charsets.UTF_8)
    }

    fun compareDocWithOperandDiagnostics() = SchemaCompareDocument(
        status = "identical",
        exitCode = 0,
        source = "file:/tmp/source.yaml",
        target = "db:seqtest",
        summary = SchemaCompareSummary(),
        diff = null,
        validation = null,
        sourceOperand = OperandInfo(
            reference = "file:/tmp/source.yaml",
            validation = ValidationResult(),
            notes = listOf(
                SchemaReadNote(
                    severity = SchemaReadSeverity.WARNING,
                    code = "W116",
                    objectName = "invoices.invoice_number",
                    message = "Degraded sequence emulation detected",
                )
            ),
            skippedObjects = listOf(
                SkippedObject(
                    type = "trigger",
                    name = "dmg_seq_invoices_invoice_number_bi",
                    reason = "Support-trigger not representable in neutral model",
                    code = "W116",
                )
            ),
        ),
        targetOperand = OperandInfo(
            reference = "db:seqtest",
            validation = ValidationResult(),
            notes = listOf(
                SchemaReadNote(
                    severity = SchemaReadSeverity.INFO,
                    code = "I100",
                    objectName = "invoice_seq",
                    message = "Sequence emulation confirmed",
                )
            ),
            skippedObjects = listOf(
                SkippedObject(
                    type = "function",
                    name = "dmg_nextval",
                    reason = "Support routine hidden from compare result",
                )
            ),
        ),
    )

    // §8.3: --help
    test("schema compare --help is reachable") {
        shouldThrow<CliktError> {
            cli().parse(listOf("schema", "compare", "--help"))
        }
    }

    // §8.3: identical -> no ProgramResult
    test("identical schemas exit successfully") {
        val src = resourcePath("valid-schema.yaml")
        shouldNotThrowAny {
            cli().parse(listOf("schema", "compare", "--source", src, "--target", src))
        }
    }

    // §8.3: different -> ProgramResult(1)
    test("different schemas exit with code 1") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema-b.yaml")))
        }
        ex.statusCode shouldBe 1
    }

    // §8.3: broken YAML -> ProgramResult(7)
    test("broken YAML exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("broken.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        ex.statusCode shouldBe 7
    }

    // §8.3: invalid schema -> ProgramResult(3)
    test("invalid schema exits with code 3") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("invalid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        ex.statusCode shouldBe 3
    }

    // §8.3: --output writes file
    test("--output writes file") {
        val tmp = Files.createTempFile("compare-", ".txt")
        try {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml"),
                "--output", tmp.toString()))
            val content = tmp.readText()
            content shouldContain "IDENTICAL"
        } finally {
            tmp.deleteIfExists()
        }
    }

    // §8.3: --output in non-existent parent
    test("--output in non-existent parent directory creates directory and writes file") {
        val tmpDir = Files.createTempDirectory("compare-parent-")
        val nested = tmpDir.resolve("sub/dir/out.txt")
        try {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml"),
                "--output", nested.toString()))
            val content = nested.readText()
            content shouldContain "IDENTICAL"
        } finally {
            nested.deleteIfExists()
            tmpDir.toFile().deleteRecursively()
        }
    }

    // §8.3: --output-format json
    test("--output-format json produces json output") {
        val output = captureStdout {
            cli().parse(listOf("--output-format", "json", "schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        output shouldContain """"command": "schema.compare""""
        output shouldContain """"status": "identical""""
    }

    // §8.3: --output-format yaml
    test("--output-format yaml produces yaml output") {
        val output = captureStdout {
            cli().parse(listOf("--output-format", "yaml", "schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml")))
        }
        output shouldContain "command: schema.compare"
        output shouldContain "status: identical"
    }

    // §8.3/M4: validation warnings on valid schemas remain visible and don't change exit code
    test("validation warnings on valid schemas do not change exit code") {
        val src = resourcePath("valid-schema-warning.yaml")
        // Schema is valid but triggers W001 (float for monetary column)
        // Exit should be 0 (identical), not 3
        shouldNotThrowAny {
            cli().parse(listOf("schema", "compare", "--source", src, "--target", src))
        }
    }

    test("validation warnings visible in json output for valid schemas") {
        val output = captureStdout {
            cli().parse(listOf("--output-format", "json", "schema", "compare",
                "--source", resourcePath("valid-schema-warning.yaml"),
                "--target", resourcePath("valid-schema-warning.yaml")))
        }
        output shouldContain """"status": "identical""""
        output shouldContain """"validation""""
        output shouldContain """"W001""""
    }

    test("json renderer includes source_operand and target_operand diagnostics") {
        val output = CompareRendererJson.render(compareDocWithOperandDiagnostics())

        output shouldContain """"source_operand": {"""
        output shouldContain """"target_operand": {"""
        output shouldContain """"reference": "file:/tmp/source.yaml""""
        output shouldContain """"reference": "db:seqtest""""
        output shouldContain """"code": "W116""""
        output shouldContain """"object_name": "invoices.invoice_number""""
        output shouldContain """"skipped_objects": [{"type": "trigger""""
        output shouldContain """"name": "dmg_nextval""""
    }

    test("yaml renderer includes source_operand and target_operand diagnostics") {
        val output = CompareRendererYaml.render(compareDocWithOperandDiagnostics())

        output shouldContain "source_operand:"
        output shouldContain "target_operand:"
        output shouldContain "reference: \"file:/tmp/source.yaml\""
        output shouldContain "reference: \"db:seqtest\""
        output shouldContain "code: \"W116\""
        output shouldContain "object_name: \"invoices.invoice_number\""
        output shouldContain "skipped_objects:"
        output shouldContain "name: \"dmg_nextval\""
    }

    // MINOR-2: write failure at CLI level returns exit 7
    test("--output to unwritable path exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", resourcePath("valid-schema.yaml"),
                "--output", "/proc/0/nonexistent/out.txt"))
        }
        ex.statusCode shouldBe 7
    }

    // ── db: operand tests ─────────────────────────────────────────

    test("file: prefix is accepted for source operand") {
        val src = resourcePath("valid-schema.yaml")
        shouldNotThrowAny {
            cli().parse(listOf("schema", "compare",
                "--source", "file:$src",
                "--target", "file:$src"))
        }
    }

    test("db: operand with unresolvable alias exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", resourcePath("valid-schema.yaml"),
                "--target", "db:nonexistent_alias"))
        }
        ex.statusCode shouldBe 7
    }

    test("db: operand as source with unresolvable alias exits with code 7") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", "db:nonexistent_alias",
                "--target", resourcePath("valid-schema.yaml")))
        }
        ex.statusCode shouldBe 7
    }

    test("schema compare scrubs credentials in cli stderr for missing-host db url parse failures") {
        val stderr = captureStderr {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf(
                    "schema", "compare",
                    "--source", resourcePath("valid-schema.yaml"),
                    "--target", "db:postgresql://admin:secret@/db?password=secret"
                ))
            }
            ex.statusCode shouldBe 7
        }

        stderr shouldContain "***"
        stderr shouldNotContain "secret"
        stderr shouldContain "db:postgresql://admin:***@/db?password=***"
    }

    test("mixed file/db operands: file source, db target with unresolvable alias") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("schema", "compare",
                "--source", "file:${resourcePath("valid-schema.yaml")}",
                "--target", "db:no_such_connection"))
        }
        ex.statusCode shouldBe 7
    }

    // §8.3: different schemas with json format
    test("different schemas with json format produces json diff") {
        val output = captureStdout {
            try {
                cli().parse(listOf("--output-format", "json", "schema", "compare",
                    "--source", resourcePath("valid-schema.yaml"),
                    "--target", resourcePath("valid-schema-b.yaml")))
            } catch (_: ProgramResult) { /* exit 1 expected */ }
        }
        output shouldContain """"status": "different""""
    }
})
