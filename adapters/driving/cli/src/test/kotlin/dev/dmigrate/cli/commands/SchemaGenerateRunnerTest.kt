package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.core.validation.ValidationWarning
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

/**
 * Unit-Tests für [SchemaGenerateRunner] mit Fakes für Schema-Reader,
 * Validator, DDL-Generator, Report-Writer und File-Writer. Damit werden
 * alle Exit-Code-Pfade (0/2/3/7) und Output-Routing-Branches
 * (json / file / stdout, rollback, report, notes, skipped, verbose,
 * quiet) direkt testbar, ohne echte YAML-Dateien, echte Generators oder
 * echtes Dateisystem.
 *
 * `printError` and `printValidationResult` are injected as lambdas into
 * the Runner; for tests that need to inspect their output we capture
 * `System.out`/`System.err` streams because the production lambdas
 * (from [OutputFormatter]) write there.
 */
class SchemaGenerateRunnerTest : FunSpec({

    // ─── Fakes ────────────────────────────────────────────────────

    /**
     * Fake-Generator, der einen konfigurierbaren [DdlResult] zurückgibt.
     * Standardmäßig liefert er ein einfaches `CREATE TABLE`-Statement; Tests
     * können Notes und Skipped-Objects injizieren, um Print-Pfade zu
     * triggern.
     */
    class FakeGenerator(
        override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL,
        val generateResult: DdlResult = DdlResult(
            statements = listOf(DdlStatement("CREATE TABLE users (id INT);")),
        ),
        val rollbackResult: DdlResult = DdlResult(
            statements = listOf(DdlStatement("DROP TABLE users;")),
        ),
    ) : DdlGenerator {
        var generateCalls = 0
        var rollbackCalls = 0
        var generateOptions: dev.dmigrate.driver.DdlGenerationOptions? = null
        var rollbackOptions: dev.dmigrate.driver.DdlGenerationOptions? = null
        val lastOptions: dev.dmigrate.driver.DdlGenerationOptions? get() = rollbackOptions ?: generateOptions
        override fun generate(schema: SchemaDefinition, options: dev.dmigrate.driver.DdlGenerationOptions): DdlResult {
            generateCalls++
            generateOptions = options
            return generateResult
        }
        override fun generateRollback(schema: SchemaDefinition, options: dev.dmigrate.driver.DdlGenerationOptions): DdlResult {
            rollbackCalls++
            rollbackOptions = options
            return rollbackResult
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────

    val defaultSchema = SchemaDefinition(name = "TestApp", version = "1.0.0")

    fun request(
        source: Path = Path.of("/tmp/schema.yaml"),
        target: String = "postgresql",
        spatialProfile: String? = null,
        output: Path? = null,
        report: Path? = null,
        generateRollback: Boolean = false,
        outputFormat: String = "plain",
        verbose: Boolean = false,
        quiet: Boolean = false,
        splitMode: SplitMode = SplitMode.SINGLE,
        mysqlNamedSequences: String? = null,
    ) = SchemaGenerateRequest(
        source = source,
        target = target,
        spatialProfile = spatialProfile,
        output = output,
        report = report,
        generateRollback = generateRollback,
        outputFormat = outputFormat,
        verbose = verbose,
        quiet = quiet,
        splitMode = splitMode,
        mysqlNamedSequences = mysqlNamedSequences,
    )

    class StdoutCapture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined(): String = lines.joinToString("\n")
    }

    class StderrCapture {
        val lines = mutableListOf<String>()
        val sink: (String) -> Unit = { lines += it }
        fun joined(): String = lines.joinToString("\n")
    }

    class WriteRecord(val path: Path, val content: String)
    class ReportRecord(
        val path: Path,
        val result: DdlResult,
        val schema: SchemaDefinition,
        val dialect: String,
        val source: Path,
    )

    /**
     * Kapselt einen Runner-Aufruf samt Fakes und liefert das Tripel
     * (exitCode, stdoutLines, stderrLines) zurück. Zusätzlich expose-t
     * er die aufgezeichneten File-Writes und Report-Writes.
     */
    class RunnerHarness {
        val stdout = StdoutCapture()
        val stderr = StderrCapture()
        val fileWrites = mutableListOf<WriteRecord>()
        val reportWrites = mutableListOf<ReportRecord>()

        var schemaReader: (Path) -> SchemaDefinition = { defaultSchema }
        var validator: (SchemaDefinition) -> ValidationResult = { ValidationResult() }
        var generator: FakeGenerator = FakeGenerator()

        fun runner(): SchemaGenerateRunner = SchemaGenerateRunner(
            schemaReader = schemaReader,
            validator = validator,
            generatorLookup = { generator },
            reportWriter = { path, result, schema, dialect, source, _, _ ->
                reportWrites += ReportRecord(path, result, schema, dialect, source)
            },
            fileWriter = { path, content -> fileWrites += WriteRecord(path, content) },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { msg, _ -> stderr.sink("Error: $msg") },
            printValidationResult = { _, _, _ -> },
            stdout = stdout.sink,
            stderr = stderr.sink,
        )
    }

    val harness = { RunnerHarness() }

    // ─── Happy paths — stdout routing ────────────────────────────

    test("Exit 0: DDL is written to stdout when --output is absent") {
        val h = harness()
        h.runner().execute(request()) shouldBe 0
        h.stdout.joined() shouldContain "CREATE TABLE users"
        h.fileWrites.shouldBeEmpty()
        h.reportWrites.shouldBeEmpty()
    }

    test("Exit 0: --generate-rollback appends ROLLBACK block in stdout mode") {
        val h = harness()
        h.runner().execute(request(generateRollback = true)) shouldBe 0
        h.stdout.joined() shouldContain "CREATE TABLE users"
        h.stdout.joined() shouldContain "ROLLBACK"
        h.stdout.joined() shouldContain "DROP TABLE users"
        h.generator.rollbackCalls shouldBe 1
    }

    test("Exit 0: --report without --output writes the report to the given path") {
        val h = harness()
        h.runner().execute(
            request(report = Path.of("/tmp/report.yaml"))
        ) shouldBe 0
        h.reportWrites.size shouldBe 1
        h.reportWrites[0].path shouldBe Path.of("/tmp/report.yaml")
        // no file-write for the DDL itself — that went to stdout
        h.fileWrites.shouldBeEmpty()
    }

    test("Exit 0: no report written in stdout mode when --report is absent") {
        val h = harness()
        h.runner().execute(request()) shouldBe 0
        h.reportWrites.shouldBeEmpty()
    }

    // ─── Happy paths — file output routing ───────────────────────

    test("Exit 0: DDL is written to --output file") {
        val h = harness()
        val outPath = Path.of("/tmp/out.sql")
        h.runner().execute(request(output = outPath)) shouldBe 0
        h.fileWrites.map { it.path } shouldContain outPath
        h.fileWrites.first { it.path == outPath }.content shouldContain "CREATE TABLE users"
        // Sidecar report is auto-generated when --output is set
        h.reportWrites.size shouldBe 1
    }

    test("Exit 0: --output + --generate-rollback writes both DDL and rollback files") {
        val h = harness()
        h.runner().execute(
            request(output = Path.of("/tmp/out.sql"), generateRollback = true)
        ) shouldBe 0
        val paths = h.fileWrites.map { it.path.toString() }
        paths shouldContain "/tmp/out.sql"
        paths shouldContain "/tmp/out.rollback.sql"
        h.generator.rollbackCalls shouldBe 1
    }

    test("Exit 0: sidecar report is derived from --output when --report is absent") {
        val h = harness()
        h.runner().execute(request(output = Path.of("/tmp/out.sql"))) shouldBe 0
        h.reportWrites.size shouldBe 1
        h.reportWrites[0].path shouldBe Path.of("/tmp/out.report.yaml")
    }

    test("Exit 0: explicit --report overrides the sidecar path") {
        val h = harness()
        h.runner().execute(
            request(
                output = Path.of("/tmp/out.sql"),
                report = Path.of("/tmp/custom-report.yaml"),
            )
        ) shouldBe 0
        h.reportWrites[0].path shouldBe Path.of("/tmp/custom-report.yaml")
    }

    test("Exit 0: --quiet suppresses 'DDL written to ...' and 'Report written to ...' messages") {
        val h = harness()
        h.runner().execute(
            request(output = Path.of("/tmp/out.sql"), quiet = true)
        ) shouldBe 0
        h.stderr.lines.none { it.contains("DDL written to") } shouldBe true
        h.stderr.lines.none { it.contains("Report written to") } shouldBe true
        // File writes themselves still happen
        h.fileWrites.size shouldBe 1
        h.reportWrites.size shouldBe 1
    }

    test("Exit 0: non-quiet mode prints 'DDL written to ...' and 'Report written to ...'") {
        val h = harness()
        h.runner().execute(request(output = Path.of("/tmp/out.sql"))) shouldBe 0
        h.stderr.joined() shouldContain "DDL written to /tmp/out.sql"
        h.stderr.joined() shouldContain "Report written to"
    }

    test("Exit 0: --quiet + --generate-rollback still writes both files silently") {
        val h = harness()
        h.runner().execute(
            request(
                output = Path.of("/tmp/out.sql"),
                generateRollback = true,
                quiet = true,
            )
        ) shouldBe 0
        h.fileWrites.size shouldBe 2
        h.stderr.lines.none { it.contains("Rollback DDL written") } shouldBe true
    }

    // ─── Happy paths — json output routing ───────────────────────

    test("Exit 0: --output-format=json routes the result through formatJsonOutput") {
        val h = harness()
        h.runner().execute(
            request(outputFormat = "json")
        ) shouldBe 0
        h.stdout.joined() shouldContain "\"command\": \"schema.generate\""
        h.stdout.joined() shouldContain "\"target\": \"postgresql\""
        // json mode bypasses file writing and report generation
        h.fileWrites.shouldBeEmpty()
        h.reportWrites.shouldBeEmpty()
    }

    test("Exit 0: --output-format=json preserves spatial W120 notes and E052 skipped objects") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
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
                        hint = "Use --spatial-profile postgis",
                    ),
                ),
            )
        )

        h.runner().execute(request(target = "mysql", outputFormat = "json")) shouldBe 0

        h.stdout.joined() shouldContain "\"code\": \"W120\""
        h.stdout.joined() shouldContain "\"object\": \"places.location\""
        h.stdout.joined() shouldContain "\"code\": \"E052\""
        h.stdout.joined() shouldContain "\"name\": \"blocked_places\""
        h.stdout.joined() shouldContain "\"warnings\": 1"
        h.stdout.joined() shouldContain "\"action_required\": 1"
        h.fileWrites.shouldBeEmpty()
        h.reportWrites.shouldBeEmpty()
    }

    // ─── Notes & skipped objects ─────────────────────────────────

    test("prints WARNING notes to stderr") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(
                    DdlStatement(
                        "CREATE TABLE users (id INT)",
                        notes = listOf(TransformationNote(NoteType.WARNING, "W100", "users", "legacy type mapping")),
                    )
                )
            )
        )
        h.runner().execute(request()) shouldBe 0
        h.stderr.joined() shouldContain "Warning [W100]"
        h.stderr.joined() shouldContain "legacy type mapping"
    }

    test("prints ACTION_REQUIRED notes with hint to stderr") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(
                    DdlStatement(
                        "CREATE VIEW v AS SELECT 1",
                        notes = listOf(
                            TransformationNote(
                                NoteType.ACTION_REQUIRED,
                                "T099",
                                "view.v",
                                "function not supported",
                                hint = "rewrite as a stored procedure",
                            ),
                        ),
                    )
                )
            )
        )
        h.runner().execute(request()) shouldBe 0
        h.stderr.joined() shouldContain "Action required [T099]"
        h.stderr.joined() shouldContain "function not supported"
        h.stderr.joined() shouldContain "Hint: rewrite as a stored procedure"
    }

    test("ACTION_REQUIRED without hint: only the main line is printed") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(
                    DdlStatement(
                        "stmt",
                        notes = listOf(
                            TransformationNote(NoteType.ACTION_REQUIRED, "T100", "obj", "msg"),
                        ),
                    )
                )
            )
        )
        h.runner().execute(request()) shouldBe 0
        h.stderr.joined() shouldContain "Action required [T100]"
        h.stderr.lines.none { it.contains("Hint:") } shouldBe true
    }

    test("INFO notes are suppressed without --verbose") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(
                    DdlStatement(
                        "stmt",
                        notes = listOf(TransformationNote(NoteType.INFO, "I001", "obj", "fyi")),
                    )
                )
            )
        )
        h.runner().execute(request()) shouldBe 0
        h.stderr.lines.none { it.contains("Info [I001]") } shouldBe true
    }

    test("INFO notes are printed with --verbose") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(
                    DdlStatement(
                        "stmt",
                        notes = listOf(TransformationNote(NoteType.INFO, "I001", "obj", "fyi")),
                    )
                )
            )
        )
        h.runner().execute(request(verbose = true)) shouldBe 0
        h.stderr.joined() shouldContain "Info [I001]"
        h.stderr.joined() shouldContain "fyi"
    }

    test("prints skipped objects to stderr") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(DdlStatement("CREATE TABLE t (id INT);")),
                skippedObjects = listOf(
                    SkippedObject("procedure", "legacy_proc", "not supported on postgresql"),
                ),
            )
        )
        h.runner().execute(request()) shouldBe 0
        h.stderr.joined() shouldContain "Skipped procedure 'legacy_proc'"
        h.stderr.joined() shouldContain "not supported on postgresql"
    }

})
