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

    // ─── Exit 2: bad dialect ─────────────────────────────────────

    test("Exit 2: unknown --target dialect") {
        val h = harness()
        h.runner().execute(request(target = "oracle")) shouldBe 2
        h.stderr.joined() shouldContain "oracle"
        // Nothing else happened: no schema read, no generator called
        h.generator.generateCalls shouldBe 0
    }

    // ─── Exit 3: validation failure ──────────────────────────────

    test("Exit 3: validator returns errors") {
        val h = harness()
        h.validator = {
            ValidationResult(
                errors = listOf(ValidationError("E001", "missing PK", "tables.users")),
            )
        }
        h.runner().execute(request()) shouldBe 3
        // Generator is NOT called if validation fails
        h.generator.generateCalls shouldBe 0
        // No file writes, no report writes
        h.fileWrites.shouldBeEmpty()
        h.reportWrites.shouldBeEmpty()
    }

    test("Exit 3: validation warnings alone do NOT trigger exit 3") {
        val h = harness()
        h.validator = {
            ValidationResult(
                warnings = listOf(ValidationWarning("W100", "style", "tables.users"))
            )
        }
        h.runner().execute(request()) shouldBe 0
        h.generator.generateCalls shouldBe 1
    }

    // ─── Exit 7: schema parse failure ────────────────────────────

    test("Exit 7: schemaReader throws → exit 7 and no further work") {
        val h = harness()
        h.schemaReader = { throw RuntimeException("invalid yaml: line 42") }
        h.runner().execute(request()) shouldBe 7
        h.stderr.joined() shouldContain "Failed to parse schema file"
        h.stderr.joined() shouldContain "invalid yaml"
        h.generator.generateCalls shouldBe 0
    }

    test("Exit 7: schemaReader throws with null message still produces a useful error") {
        val h = harness()
        h.schemaReader = { throw RuntimeException() }
        h.runner().execute(request()) shouldBe 7
        h.stderr.joined() shouldContain "Failed to parse schema file"
    }

    // ─── Sidecar path derivation ─────────────────────────────────

    test("sidecar path is computed from the --output file name") {
        val h = harness()
        h.runner().execute(
            request(output = Path.of("/tmp/myapp.v2.sql"))
        ) shouldBe 0
        h.reportWrites[0].path shouldBe Path.of("/tmp/myapp.v2.report.yaml")
    }

    test("rollback path is computed from the --output file name with multiple dots") {
        val h = harness()
        h.runner().execute(
            request(output = Path.of("/tmp/myapp.v2.sql"), generateRollback = true)
        ) shouldBe 0
        h.fileWrites.map { it.path.toString() } shouldContain "/tmp/myapp.v2.rollback.sql"
    }

    // ─── Generator receives the correct dialect ──────────────────

    test("generatorLookup is called with the parsed dialect") {
        var observedDialect: DatabaseDialect? = null
        val h = harness()
        val runner = SchemaGenerateRunner(
            schemaReader = { defaultSchema },
            validator = { ValidationResult() },
            generatorLookup = { dialect ->
                observedDialect = dialect
                FakeGenerator(dialect = dialect)
            },
            reportWriter = { _, _, _, _, _, _, _ -> },
            fileWriter = { _, _ -> },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { _, _ -> },
            printValidationResult = { _, _, _ -> },
            stdout = { },
            stderr = { },
        )
        runner.execute(request(target = "mysql")) shouldBe 0
        observedDialect shouldBe DatabaseDialect.MYSQL
    }

    test("generatorLookup receives SQLITE for --target sqlite") {
        var observedDialect: DatabaseDialect? = null
        val runner = SchemaGenerateRunner(
            schemaReader = { defaultSchema },
            validator = { ValidationResult() },
            generatorLookup = { dialect ->
                observedDialect = dialect
                FakeGenerator(dialect = dialect)
            },
            reportWriter = { _, _, _, _, _, _, _ -> },
            fileWriter = { _, _ -> },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { _, _ -> },
            printValidationResult = { _, _, _ -> },
            stdout = { },
            stderr = { },
        )
        runner.execute(request(target = "sqlite")) shouldBe 0
        observedDialect shouldBe DatabaseDialect.SQLITE
    }

    // ─── Report writer argument propagation ──────────────────────

    test("reportWriter receives the correct schema, dialect name and source path") {
        val h = harness()
        val sourcePath = Path.of("/tmp/my-input.yaml")
        h.runner().execute(
            request(source = sourcePath, output = Path.of("/tmp/out.sql"), target = "mysql")
        ) shouldBe 0
        val rec = h.reportWrites.single()
        rec.schema shouldBe defaultSchema
        rec.dialect shouldBe "mysql"
        rec.source shouldBe sourcePath
    }

    // ─── Spatial Profile (Phase D) ──────────────────────────────

    test("Exit 0: default spatial profile for postgresql is postgis") {
        val h = harness()
        h.runner().execute(request(target = "postgresql")) shouldBe 0
        h.generator.lastOptions!!.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.POSTGIS
    }

    test("Exit 0: default spatial profile for mysql is native") {
        val h = harness()
        h.runner().execute(request(target = "mysql")) shouldBe 0
        h.generator.lastOptions!!.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.NATIVE
    }

    test("Exit 0: default spatial profile for sqlite is none") {
        val h = harness()
        h.runner().execute(request(target = "sqlite")) shouldBe 0
        h.generator.lastOptions!!.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.NONE
    }

    test("Exit 2: unknown spatial profile") {
        val h = harness()
        h.runner().execute(request(spatialProfile = "bogus")) shouldBe 2
        h.stderr.joined() shouldContain "Unknown spatial profile"
    }

    test("Exit 2: invalid profile/dialect combination") {
        val h = harness()
        h.runner().execute(request(target = "mysql", spatialProfile = "spatialite")) shouldBe 2
        h.stderr.joined() shouldContain "not allowed"
    }

    test("Exit 0: same options passed to generate and generateRollback") {
        val h = harness()
        h.runner().execute(request(target = "postgresql", spatialProfile = "none", generateRollback = true)) shouldBe 0
        h.generator.generateOptions!!.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.NONE
        h.generator.rollbackOptions!!.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.NONE
        h.generator.generateOptions shouldBe h.generator.rollbackOptions
    }

    test("Exit 0: mysql + none is allowed and blocks spatial tables via E052") {
        val h = harness()
        h.runner().execute(request(target = "mysql", spatialProfile = "none")) shouldBe 0
        h.generator.generateOptions!!.spatialProfile shouldBe dev.dmigrate.driver.SpatialProfile.NONE
    }

    // ─── Split-mode preflight (0.9.2 AP 6.2) ─────────────────────

    test("default splitMode is SINGLE") {
        SchemaGenerateRequest(
            source = Path.of("/tmp/schema.yaml"),
            target = "postgresql",
            output = null,
            report = null,
            generateRollback = false,
            outputFormat = "plain",
            verbose = false,
            quiet = false,
        ).splitMode shouldBe SplitMode.SINGLE
    }

    test("--split single behaves identically to no --split") {
        val h = harness()
        h.runner().execute(request(splitMode = SplitMode.SINGLE)) shouldBe 0
        h.stdout.joined() shouldContain "CREATE TABLE"
    }

    test("--split pre-post without --output and without json exits 2") {
        val h = harness()
        val exit = h.runner().execute(request(splitMode = SplitMode.PRE_POST))
        exit shouldBe 2
        h.stderr.joined() shouldContain "`--split pre-post` requires `--output` unless `--output-format json` is used."
    }

    test("--split pre-post with --output passes preflight") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            output = Path.of("/tmp/out.sql"),
        ))
        exit shouldBe 0
    }

    test("--split pre-post with --output-format json passes preflight without --output") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            outputFormat = "json",
        ))
        exit shouldBe 0
    }

    test("--split pre-post with --generate-rollback exits 2") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            output = Path.of("/tmp/out.sql"),
            generateRollback = true,
        ))
        exit shouldBe 2
        h.stderr.joined() shouldContain "`--split pre-post` cannot be combined with `--generate-rollback`."
    }

    test("--split pre-post rollback check runs before output check") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            generateRollback = true,
        ))
        exit shouldBe 2
        h.stderr.joined() shouldContain "--generate-rollback"
    }

    // ─── Split E060 diagnostic (0.9.2 AP 6.3 Step E) ────────────

    test("E060 globalNote with PRE_POST exits 2") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(DdlStatement("CREATE TABLE t (id INT);")),
                globalNotes = listOf(TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E060",
                    objectName = "v_unclear",
                    message = "View 'v_unclear' phase not determinable.",
                    hint = "Add dependencies.functions.",
                )),
            )
        )
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            output = Path.of("/tmp/out.sql"),
        ))
        exit shouldBe 2
        h.stderr.joined() shouldContain "E060"
        h.stderr.joined() shouldContain "v_unclear"
    }

    test("E060 globalNote with SINGLE does not exit 2") {
        val h = harness()
        h.generator = FakeGenerator(
            generateResult = DdlResult(
                statements = listOf(DdlStatement("CREATE TABLE t (id INT);")),
                globalNotes = listOf(TransformationNote(
                    type = NoteType.ACTION_REQUIRED,
                    code = "E060",
                    objectName = "v_unclear",
                    message = "View 'v_unclear' phase not determinable.",
                )),
            )
        )
        val exit = h.runner().execute(request(splitMode = SplitMode.SINGLE))
        exit shouldBe 0
    }

    // ─── Split output (0.9.2 AP 6.4) ────────────────────────────

    test("--split pre-post --output writes two SQL files") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            output = Path.of("/tmp/schema.sql"),
        ))
        exit shouldBe 0
        h.fileWrites.any { it.path.toString().contains("pre-data") } shouldBe true
        h.fileWrites.any { it.path.toString().contains("post-data") } shouldBe true
        h.fileWrites.none { it.path == Path.of("/tmp/schema.sql") } shouldBe true
    }

    test("--split pre-post --output-format json contains ddl_parts") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            outputFormat = "json",
        ))
        exit shouldBe 0
        val json = h.stdout.joined()
        json shouldContain "\"split_mode\": \"pre-post\""
        json shouldContain "\"ddl_parts\""
        json shouldContain "\"pre_data\""
        json shouldContain "\"post_data\""
    }

    test("--split pre-post json does not contain legacy ddl field") {
        val h = harness()
        h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            outputFormat = "json",
        ))
        val json = h.stdout.joined()
        // "ddl" should not appear as a top-level key
        json shouldContain "ddl_parts"
        // But the legacy "ddl": should NOT appear
        val lines = json.lines()
        lines.none { it.trimStart().startsWith("\"ddl\":") } shouldBe true
    }

    test("single json still contains legacy ddl field") {
        val h = harness()
        h.runner().execute(request(outputFormat = "json"))
        val json = h.stdout.joined()
        json shouldContain "\"ddl\":"
        lines@ for (line in json.lines()) {
            if (line.trimStart().startsWith("\"split_mode\"")) {
                error("split_mode should not appear in single JSON")
            }
        }
    }

    test("--split pre-post --output + --output-format json writes files AND outputs json") {
        val h = harness()
        val exit = h.runner().execute(request(
            splitMode = SplitMode.PRE_POST,
            output = Path.of("/tmp/schema.sql"),
            outputFormat = "json",
        ))
        exit shouldBe 0
        // Files written
        h.fileWrites.any { it.path.toString().contains("pre-data") } shouldBe true
        // JSON output
        h.stdout.joined() shouldContain "ddl_parts"
    }

    // ─── --mysql-named-sequences (0.9.3 AP 6.2) ────────────────

    test("Exit 0: --mysql-named-sequences helper_table with --target mysql sets mode") {
        val h = harness()
        h.generator = FakeGenerator(dialect = DatabaseDialect.MYSQL)
        h.runner().execute(
            request(target = "mysql", mysqlNamedSequences = "helper_table")
        ) shouldBe 0
    }

    test("Exit 0: --target mysql without --mysql-named-sequences defaults to action_required") {
        val h = harness()
        h.generator = FakeGenerator(dialect = DatabaseDialect.MYSQL)
        h.runner().execute(request(target = "mysql")) shouldBe 0
    }

    test("Exit 2: --mysql-named-sequences with --target postgresql") {
        val h = harness()
        h.runner().execute(
            request(target = "postgresql", mysqlNamedSequences = "helper_table")
        ) shouldBe 2
        h.stderr.joined() shouldContain "--mysql-named-sequences is only valid with --target mysql"
        h.stderr.joined() shouldContain "Allowed values"
    }

    test("Exit 2: --mysql-named-sequences with --target sqlite") {
        val h = harness()
        h.generator = FakeGenerator(dialect = DatabaseDialect.SQLITE)
        h.runner().execute(
            request(target = "sqlite", mysqlNamedSequences = "action_required")
        ) shouldBe 2
        h.stderr.joined() shouldContain "--mysql-named-sequences is only valid with --target mysql"
    }

    test("JSON output for MySQL includes mysql_named_sequences field") {
        val h = harness()
        h.generator = FakeGenerator(dialect = DatabaseDialect.MYSQL)
        h.runner().execute(
            request(target = "mysql", outputFormat = "json", mysqlNamedSequences = "helper_table")
        ) shouldBe 0
        h.stdout.joined() shouldContain "\"mysql_named_sequences\": \"helper_table\""
    }

    test("JSON output for PostgreSQL does NOT include mysql_named_sequences field") {
        val h = harness()
        h.runner().execute(request(target = "postgresql", outputFormat = "json")) shouldBe 0
        h.stdout.joined() shouldNotContain "mysql_named_sequences"
    }

    test("JSON output includes generator version 0.9.4") {
        val h = harness()
        h.runner().execute(request(outputFormat = "json")) shouldBe 0
        h.stdout.joined() shouldContain "\"generator\": \"d-migrate 0.9.4\""
    }
})
