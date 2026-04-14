package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.*
import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class SchemaCompareRunnerTest : FunSpec({

    val schemaA = SchemaDefinition(name = "A", version = "1.0")
    val emptyDiff = SchemaDiff()
    val nonEmptyDiff = SchemaDiff(
        schemaMetadata = SchemaMetadataDiff(
            name = ValueChange("A", "B"),
            version = ValueChange("1.0", "2.0"),
        ),
        tablesAdded = listOf(NamedTable("users", TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier())),
        ))),
    )
    val fakeDiffView = DiffView(
        schemaMetadata = MetadataChangeView(name = StringChange("A", "B")),
        tablesAdded = listOf(TableSummaryView("users", 1)),
    )

    val fakeOperand = ResolvedSchemaOperand(
        reference = "/tmp/a.yaml",
        schema = schemaA,
        validation = ValidationResult(),
    )

    class Capture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined() = lines.joinToString("\n")
    }

    class Harness {
        val stdout = Capture()
        val stderr = Capture()
        val fileWrites = mutableListOf<Pair<Path, String>>()
        val dirsCreated = mutableListOf<Path>()

        var fileSchema: SchemaDefinition = schemaA
        var comparatorResult: SchemaDiff = emptyDiff

        fun runner() = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = fileSchema,
                    validation = ValidationResult(),
                )
            },
            comparator = { _, _ -> comparatorResult },
            projectDiff = { _ -> fakeDiffView },
            ensureParentDirectories = { dirsCreated.add(it) },
            fileWriter = { path, content -> fileWrites += path to content },
            renderPlain = { doc -> "PLAIN:${doc.status}" },
            renderJson = { doc -> """{"status":"${doc.status}"}""" },
            renderYaml = { doc -> "status: ${doc.status}" },
            printError = { msg, _ -> stderr.sink("Error: $msg") },
            stdout = stdout.sink,
            stderr = stderr.sink,
        )
    }

    fun request(
        source: String = "/tmp/a.yaml",
        target: String = "/tmp/b.yaml",
        output: Path? = null,
        outputFormat: String = "plain",
        quiet: Boolean = false,
    ) = SchemaCompareRequest(source, target, output, outputFormat, quiet)

    // ── Backward-compat file/file ───────────────

    test("identical schemas return exit 0") {
        val h = Harness()
        h.runner().execute(request()) shouldBe 0
        h.stdout.joined() shouldContain "identical"
    }

    test("different schemas return exit 1") {
        val h = Harness()
        h.comparatorResult = nonEmptyDiff
        h.runner().execute(request()) shouldBe 1
        h.stdout.joined() shouldContain "different"
    }

    test("source parse failure returns exit 7") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { throw RuntimeException("bad yaml") },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { msg, _ -> h.stderr.sink("Error: $msg") },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 7
        h.stderr.joined() shouldContain "Failed to read"
    }

    test("invalid source returns exit 3") {
        val h = Harness()
        var calls = 0
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                calls++
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schemaA,
                    validation = if (calls == 1) ValidationResult(errors = listOf(
                        ValidationError("E001", "no columns", "tables.t")))
                    else ValidationResult(),
                )
            },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { msg, _ -> h.stderr.sink("Error: $msg") },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 3
        h.stdout.joined() shouldContain "invalid"
    }

    test("validation warnings do not suppress compare result") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schemaA,
                    validation = ValidationResult(warnings = listOf(
                        ValidationWarning("W001", "some warning", "tables.t"))),
                )
            },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 0
        h.stderr.joined() shouldContain "Warning"
    }

    test("output collision with source returns exit 2") {
        val h = Harness()
        val path = Path.of("/tmp/a.yaml")
        h.runner().execute(request(source = path.toString(), output = path)) shouldBe 2
        h.stderr.joined() shouldContain "must not be the same"
    }

    test("json rendering is selected by output format") {
        val h = Harness()
        h.runner().execute(request(outputFormat = "json")) shouldBe 0
        h.stdout.joined() shouldContain """"status":"identical""""
    }

    test("yaml rendering is selected by output format") {
        val h = Harness()
        h.runner().execute(request(outputFormat = "yaml")) shouldBe 0
        h.stdout.joined() shouldContain "status: identical"
    }

    test("quiet mode suppresses output for identical schemas") {
        val h = Harness()
        h.runner().execute(request(quiet = true)) shouldBe 0
        h.stdout.lines.size shouldBe 0
    }

    test("quiet mode still shows output for different schemas") {
        val h = Harness()
        h.comparatorResult = nonEmptyDiff
        h.runner().execute(request(quiet = true)) shouldBe 1
        h.stdout.joined() shouldContain "different"
    }

    test("creates missing parent directories before writing output") {
        val h = Harness()
        val output = Path.of("/tmp/sub/dir/out.txt")
        h.runner().execute(request(output = output)) shouldBe 0
        h.dirsCreated.size shouldBe 1
        h.fileWrites.size shouldBe 1
    }

    // ── DB operands ─────────────────────────────

    test("db: operand triggers dbLoader") {
        val h = Harness()
        var dbCalled = false
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(op.path.toString(), schemaA, ValidationResult())
            },
            dbLoader = { _, _ ->
                dbCalled = true
                ResolvedSchemaOperand("db:staging", schemaA, ValidationResult())
            },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request(source = "/tmp/a.yaml", target = "db:staging")) shouldBe 0
        dbCalled shouldBe true
    }

    test("db: connection failure returns exit 4") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(op.path.toString(), schemaA, ValidationResult())
            },
            dbLoader = { _, _ -> throw RuntimeException("connection refused") },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { msg, _ -> h.stderr.sink("Error: $msg") },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request(target = "db:staging")) shouldBe 4
    }

    test("db: without dbLoader returns exit 2") {
        val h = Harness()
        h.runner().execute(request(target = "db:staging")) shouldBe 2
    }

    // ── Reverse marker normalization ────────────

    test("reverse markers do not produce fake metadata diff") {
        val h = Harness()
        val reverseName = dev.dmigrate.core.identity.ReverseScopeCodec.postgresName("db", "public")
        val reverseSchema = SchemaDefinition(
            name = reverseName,
            version = dev.dmigrate.core.identity.ReverseScopeCodec.REVERSE_VERSION,
        )
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(op.path.toString(), reverseSchema, ValidationResult())
            },
            comparator = { left, right ->
                // After normalization, both should have same name/version
                if (left.name != right.name || left.version != right.version) {
                    SchemaDiff(schemaMetadata = SchemaMetadataDiff(
                        name = ValueChange(left.name, right.name)))
                } else {
                    SchemaDiff()
                }
            },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        // Both operands are reverse-generated with same schema → identical after normalization
        runner.execute(request()) shouldBe 0
    }

    test("invalid reverse marker returns exit 7") {
        val h = Harness()
        val badSchema = SchemaDefinition(name = "__dmigrate_reverse__:broken", version = "1.0")
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(op.path.toString(), badSchema, ValidationResult())
            },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { msg, _ -> h.stderr.sink("Error: $msg") },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 7
    }
})
