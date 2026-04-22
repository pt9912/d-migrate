package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.*
import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.SkippedObject
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

class SchemaCompareRunnerTestPart2 : FunSpec({

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

    // ── E1a/E1c: Operand-side notes and exit-code contract ─────

    val syntheticW116 = SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = "W116",
        objectName = "dmg_sequences",
        message = "Degraded sequence emulation detected",
    )

    val syntheticSkipped = SkippedObject(
        type = "trigger",
        name = "trg_users_id_seq",
        reason = "Support-trigger not representable in neutral model",
        code = "W116",
    )

    fun operandWithW116(ref: String, schema: SchemaDefinition = schemaA) = ResolvedSchemaOperand(
        reference = ref,
        schema = schema,
        validation = ValidationResult(),
        notes = listOf(syntheticW116),
        skippedObjects = listOf(syntheticSkipped),
    )

    test("operand W116 without diff returns exit 0") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
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
    }

    test("operand W116 with real diff returns exit 1") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
            comparator = { _, _ -> nonEmptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 1
    }

    test("validation error with operand notes returns exit 3") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schemaA,
                    validation = ValidationResult(errors = listOf(
                        ValidationError("E001", "no columns", "tables.t"))),
                    notes = listOf(syntheticW116),
                    skippedObjects = listOf(syntheticSkipped),
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
        runner.execute(request()) shouldBe 3
    }

    test("operand notes do not bleed into CompareValidation") {
        val h = Harness()
        var capturedDoc: SchemaCompareDocument? = null
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { doc ->
                capturedDoc = doc
                "PLAIN:${doc.status}"
            },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 0

        // validation must be null (no schema warnings) — operand notes stay separate
        capturedDoc!!.validation shouldBe null
        // operand info must carry the notes
        capturedDoc!!.sourceOperand!!.notes.size shouldBe 1
        capturedDoc!!.sourceOperand!!.notes[0].code shouldBe "W116"
        capturedDoc!!.sourceOperand!!.skippedObjects.size shouldBe 1
        capturedDoc!!.targetOperand!!.notes.size shouldBe 1
    }

    test("operand notes appear on stderr only for plain format") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )

        // plain: stderr should contain W116
        runner.execute(request(outputFormat = "plain")) shouldBe 0
        h.stderr.joined() shouldContain "W116"
    }

    test("operand notes do not appear on stderr for json format") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request(outputFormat = "json")) shouldBe 0
        h.stderr.joined() shouldNotContain "W116"
    }

    test("operand notes do not appear on stderr for yaml format") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request(outputFormat = "yaml")) shouldBe 0
        h.stderr.joined() shouldNotContain "W116"
    }

    test("file-vs-file with W116 on both sides but identical model stays exit 0") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
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
        h.stdout.joined() shouldContain "identical"
    }

    test("file-vs-db with W116 on target only follows real diff") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(op.path.toString(), schemaA, ValidationResult())
            },
            dbLoader = { _, _ -> operandWithW116("db:staging") },
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
    }

    test("db-vs-db with W116 on both sides but identical model stays exit 0") {
        val h = Harness()
        val runner = SchemaCompareRunner(
            fileLoader = { op -> operandWithW116(op.path.toString()) },
            dbLoader = { op, _ -> operandWithW116("db:${op.source}") },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { "PLAIN:${it.status}" },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request(source = "db:prod", target = "db:staging")) shouldBe 0
        h.stdout.joined() shouldContain "identical"
    }

    test("validation error plus operand notes: document separates them correctly") {
        val h = Harness()
        var capturedDoc: SchemaCompareDocument? = null
        val runner = SchemaCompareRunner(
            fileLoader = { op ->
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schemaA,
                    validation = ValidationResult(errors = listOf(
                        ValidationError("E001", "no columns", "tables.t"))),
                    notes = listOf(syntheticW116),
                )
            },
            comparator = { _, _ -> emptyDiff },
            projectDiff = { fakeDiffView },
            renderPlain = { doc ->
                capturedDoc = doc
                "PLAIN:${doc.status}"
            },
            renderJson = { """{"status":"${it.status}"}""" },
            renderYaml = { "status: ${it.status}" },
            printError = { _, _ -> },
            stdout = h.stdout.sink,
            stderr = h.stderr.sink,
        )
        runner.execute(request()) shouldBe 3

        // validation contains the error, not the operand note
        capturedDoc!!.validation!!.source!!.errors.size shouldBe 1
        capturedDoc!!.validation!!.source!!.errors[0].code shouldBe "E001"
        // operand info carries the note separately
        capturedDoc!!.sourceOperand!!.notes.size shouldBe 1
        capturedDoc!!.sourceOperand!!.notes[0].code shouldBe "W116"
    }
})
