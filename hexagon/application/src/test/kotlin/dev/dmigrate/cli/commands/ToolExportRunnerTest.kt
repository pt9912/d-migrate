package dev.dmigrate.cli.commands

import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationError
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DdlGenerator
import dev.dmigrate.driver.DdlResult
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.integration.FlywayMigrationExporter
import dev.dmigrate.migration.MigrationTool
import dev.dmigrate.migration.ToolMigrationExporter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class ToolExportRunnerTest : FunSpec({

    val schema = SchemaDefinition(name = "Shop", version = "1.0.0")
    val ddlResult = DdlResult(listOf(
        DdlStatement("-- Target: postgresql"),
        DdlStatement("CREATE TABLE users (id SERIAL PRIMARY KEY);"),
    ))
    val rollbackResult = DdlResult(listOf(
        DdlStatement(
            "DROP TABLE users;",
            notes = listOf(
                TransformationNote(NoteType.WARNING, "RB001", "users", "Full table drop"),
            ),
        ),
    ))

    val fakeGenerator = object : DdlGenerator {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun generate(schema: SchemaDefinition, options: DdlGenerationOptions) = ddlResult
        override fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions) = rollbackResult
    }

    val writtenFiles = mutableMapOf<String, String>()
    val stderrLines = mutableListOf<String>()

    fun runner(
        schemaReader: (Path) -> SchemaDefinition = { schema },
        validator: (SchemaDefinition) -> ValidationResult = { ValidationResult(emptyList(), emptyList()) },
        existingPaths: (Path) -> Set<String> = { emptySet() },
        exporterLookup: (MigrationTool) -> ToolMigrationExporter = { FlywayMigrationExporter() },
        reportWriter: (Path, ToolExportReportData) -> Unit = { path, data ->
            writtenFiles[path.toString()] = ToolExportReportRenderer.render(data)
        },
    ) = ToolExportRunner(
        schemaReader = schemaReader,
        validator = validator,
        generatorLookup = { fakeGenerator },
        exporterLookup = exporterLookup,
        fileWriter = { path, content -> writtenFiles[path.toString()] = content },
        reportWriter = reportWriter,
        existingPaths = existingPaths,
        mkdirs = {},
        stderr = { stderrLines += it },
    )

    fun request(
        tool: MigrationTool = MigrationTool.FLYWAY,
        version: String? = "1.0",
        target: String = "postgresql",
        generateRollback: Boolean = false,
        report: Path? = null,
        spatialProfile: String? = null,
    ) = ToolExportRequest(
        tool = tool,
        source = Path.of("schema.yaml"),
        output = Path.of("out"),
        target = target,
        version = version,
        spatialProfile = spatialProfile,
        generateRollback = generateRollback,
        report = report,
        verbose = false,
        quiet = false,
    )

    beforeEach {
        writtenFiles.clear()
        stderrLines.clear()
    }

    // ── Success path ──────────────────────────────────────────

    test("successful Flyway export returns 0 and writes files") {
        val exit = runner().execute(request())
        exit shouldBe 0
        writtenFiles.size shouldBe 1
        writtenFiles.keys.first() shouldContain "V1.0__shop.sql"
    }

    test("successful export with rollback writes two files") {
        val exit = runner().execute(request(generateRollback = true))
        exit shouldBe 0
        writtenFiles.size shouldBe 2
    }

    // ── Exit 2: CLI validation ───────────────────────────────

    test("invalid dialect returns exit 2") {
        val exit = runner().execute(request(target = "oracle"))
        exit shouldBe 2
        stderrLines.any { it.contains("Unknown") } shouldBe true
    }

    test("Django without --version returns exit 2") {
        val exit = runner().execute(request(tool = MigrationTool.DJANGO, version = null))
        exit shouldBe 2
        stderrLines.any { it.contains("--version") } shouldBe true
    }

    test("Knex without --version returns exit 2") {
        val exit = runner().execute(request(tool = MigrationTool.KNEX, version = null))
        exit shouldBe 2
        stderrLines.any { it.contains("--version") } shouldBe true
    }

    test("invalid spatial profile returns exit 2") {
        val exit = runner().execute(request(spatialProfile = "unknown"))
        exit shouldBe 2
    }

    // ── Exit 3: validation failure ───────────────────────────

    test("schema validation failure returns exit 3") {
        val invalid = ValidationResult(
            listOf(ValidationError("E001", "test error", "table")),
            emptyList(),
        )
        val exit = runner(validator = { invalid }).execute(request())
        exit shouldBe 3
    }

    // ── Exit 7: parse/I/O/collision errors ───────────────────

    test("schema parse failure returns exit 7") {
        val exit = runner(
            schemaReader = { throw RuntimeException("parse error") }
        ).execute(request())
        exit shouldBe 7
        stderrLines.any { it.contains("parse") } shouldBe true
    }

    test("existing file collision returns exit 7") {
        val exit = runner(
            existingPaths = { setOf("V1.0__shop.sql") }
        ).execute(request())
        exit shouldBe 7
        stderrLines.any { it.contains("already exists") } shouldBe true
    }

    // ── Flyway version fallback ──────────────────────────────

    test("Flyway uses schema.version fallback when no --version") {
        val exit = runner().execute(request(version = null))
        exit shouldBe 0
        writtenFiles.keys.first() shouldContain "V1.0.0__shop.sql"
    }

    // ── Liquibase ────────────────────────────────────────────

    test("Liquibase export succeeds") {
        val exit = runner(
            exporterLookup = { dev.dmigrate.integration.LiquibaseMigrationExporter() }
        ).execute(request(tool = MigrationTool.LIQUIBASE))
        exit shouldBe 0
        writtenFiles.size shouldBe 1
        writtenFiles.keys.first() shouldContain "changelog"
    }

    // ── Django ───────────────────────────────────────────────

    test("Django export with explicit version succeeds") {
        val exit = runner(
            exporterLookup = { dev.dmigrate.integration.DjangoMigrationExporter() }
        ).execute(request(tool = MigrationTool.DJANGO, version = "0001"))
        exit shouldBe 0
        writtenFiles.keys.first() shouldContain "0001.py"
    }

    // ── Knex ─────────────────────────────────────────────────

    test("Knex export with explicit version succeeds") {
        val exit = runner(
            exporterLookup = { dev.dmigrate.integration.KnexMigrationExporter() }
        ).execute(request(tool = MigrationTool.KNEX, version = "20260414120000"))
        exit shouldBe 0
        writtenFiles.keys.first() shouldContain "20260414120000.js"
    }

    // ── Report ───────────────────────────────────────────────

    test("--report writes report sidecar") {
        val exit = runner().execute(request(report = Path.of("export.report.yaml")))
        exit shouldBe 0
        writtenFiles.containsKey("export.report.yaml") shouldBe true
        val report = writtenFiles["export.report.yaml"]!!
        report shouldContain "tool: flyway"
        report shouldContain "dialect: postgresql"
        report shouldContain "artifacts:"
    }

    test("report write failure returns exit 7") {
        val exit = runner(
            reportWriter = { _, _ -> throw RuntimeException("disk full") }
        ).execute(request(report = Path.of("report.yaml")))
        exit shouldBe 7
        stderrLines.any { it.contains("disk full") } shouldBe true
    }

    test("no report written when --report not specified") {
        val exit = runner().execute(request(report = null))
        exit shouldBe 0
        writtenFiles.keys.none { it.endsWith(".yaml") || it.endsWith(".report") } shouldBe true
    }

    // ── Rollback diagnostics ─────────────────────────────────

    test("down-path notes are printed on stderr") {
        val exit = runner().execute(request(generateRollback = true))
        exit shouldBe 0
        stderrLines.any { it.contains("RB001") } shouldBe true
    }

    test("report includes rollback notes") {
        val exit = runner().execute(request(
            generateRollback = true,
            report = Path.of("report.yaml"),
        ))
        exit shouldBe 0
        val report = writtenFiles["report.yaml"]!!
        report shouldContain "rollbackNotes:"
        report shouldContain "RB001"
    }

    test("report includes rollback skippedObjects") {
        val generatorWithSkips = object : DdlGenerator {
            override val dialect = DatabaseDialect.POSTGRESQL
            override fun generate(s: SchemaDefinition, o: DdlGenerationOptions) = ddlResult
            override fun generateRollback(s: SchemaDefinition, o: DdlGenerationOptions) =
                DdlResult(
                    listOf(DdlStatement("DROP TABLE users;")),
                    skippedObjects = listOf(
                        dev.dmigrate.driver.SkippedObject("view", "user_summary", "Views not reversible"),
                    ),
                )
        }
        val exit = ToolExportRunner(
            schemaReader = { schema },
            generatorLookup = { generatorWithSkips },
            exporterLookup = { FlywayMigrationExporter() },
            fileWriter = { path, content -> writtenFiles[path.toString()] = content },
            reportWriter = { path, data -> writtenFiles[path.toString()] = ToolExportReportRenderer.render(data) },
            existingPaths = { emptySet() },
            mkdirs = {},
            stderr = { stderrLines += it },
        ).execute(request(generateRollback = true, report = Path.of("report.yaml")))
        exit shouldBe 0
        val report = writtenFiles["report.yaml"]!!
        report shouldContain "rollbackSkippedObjects:"
        report shouldContain "user_summary"
    }

    // ── Report/artifact collision ──────────────────────────────

    test("report path colliding with artifact returns exit 7") {
        val exit = runner().execute(request(
            report = Path.of("out/V1.0__shop.sql"),
        ))
        exit shouldBe 7
        stderrLines.any { it.contains("collides with artifact") } shouldBe true
    }

    test("non-normalized report path colliding with artifact returns exit 7") {
        val exit = runner().execute(request(
            report = Path.of("out/./V1.0__shop.sql"),
        ))
        exit shouldBe 7
        stderrLines.any { it.contains("collides with artifact") } shouldBe true
    }

    test("existing report file returns exit 7") {
        val exit = runner(
            existingPaths = { setOf("my-report.yaml") }
        ).execute(request(
            report = Path.of("out/my-report.yaml"),
        ))
        exit shouldBe 7
        stderrLines.any { it.contains("already exists") } shouldBe true
    }

    // ── existingPaths I/O error ──────────────────────────────

    test("existingPaths failure returns exit 7") {
        val exit = runner(
            existingPaths = { throw java.io.IOException("permission denied") }
        ).execute(request())
        exit shouldBe 7
        stderrLines.any { it.contains("permission denied") } shouldBe true
    }
})
