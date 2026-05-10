package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * F.6.e — Erweiterte CLI-Exit-Code-Tests per Plan §F.6:
 *
 * "stdout-vs-file-Ausgabeziele, fehlende implizite Report-Sidecars,
 *  Flag-Kombinationen, die in E.1-E.6-Lueken stehen."
 *
 * The mainline cases (`--execute` ohne `--report`,
 * `--generate-rollback` ohne `--rollback-output`, etc.) sind in
 * `SchemaMigrateRunnerTest` schon abgedeckt — F.6.e schliesst die
 * verbliebenen Edge-Lueken:
 *
 * - `--dry-run --execute` mutually exclusive (Exit 2)
 * - `--execute --plan-only` mutually exclusive (Exit 2)
 * - `--execute` mit File-Target (Exit 2)
 * - Up-SQL stdout-Echo wenn `--output` fehlt (positive)
 * - `--report`-Pfad collidiert mit Source/Target (Exit 2)
 * - Invalid operand parsing (Exit 2)
 * - KEIN implizites Report-Sidecar an einem Default-Pfad
 *   (`<output>.report.json`, `<source>.report.json`, etc.) wird
 *   geschrieben wenn `--report` nicht gesetzt ist
 */
class SchemaMigrateRunnerCliExitCodeTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-cli-exit-test")

    val sourcePath = tmpDir.resolve("source.yaml")
    val targetPath = tmpDir.resolve("target.yaml")
    Files.writeString(sourcePath, "# source")
    Files.writeString(targetPath, "# target")

    fun fakeRendered(): MigrationDdlResult = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = "CREATE TABLE x (id INT);",
                operationIds = setOf("op-1"),
                risk = dev.dmigrate.core.diff.migration.OperationRisk.SAFE,
                phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
            ),
        ),
        operationsRendered = setOf("op-1"),
    )

    fun schemaWithTable(name: String) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            name to TableDefinition(
                columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun captureRunner(): Pair<SchemaMigrateRunner, MutableMap<String, String>> {
        val capture = mutableMapOf<String, String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                val (schema, validation) = if (op.path == sourcePath) {
                    schemaWithTable("orders") to ValidationResult()
                } else {
                    SchemaDefinition(name = "App", version = "1") to ValidationResult()
                }
                ResolvedSchemaOperand(
                    reference = "file:${op.path.fileName}",
                    schema = schema,
                    validation = validation,
                )
            },
            normalizer = { it },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            planner = DiffPlanner(),
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ) = fakeRendered()

                    override fun generateDown(
                        diff: dev.dmigrate.core.diff.migration.DiffResult,
                        options: DdlGenerationOptions,
                    ) = fakeRendered()
                }
            },
            atomicWriter = { p, c -> capture["wrote:$p"] = c; Files.writeString(p, c) },
            renderReport = { r, _ -> "{\"status\":\"${r.status}\",\"exitCode\":${r.exitCode}}" },
            printError = { msg, src -> capture["error:$src"] = msg },
            stdout = { capture.merge("stdout", it) { a, b -> "$a\n$b" } },
            stderr = { capture.merge("stderr", it) { a, b -> "$a\n$b" } },
        )
        return runner to capture
    }

    test("F.6.e — --dry-run and --execute are mutually exclusive (exit 2)") {
        val (runner, capture) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            dryRun = true,
            execute = true,
            report = tmpDir.resolve("dry-exec.report.json"),
        )
        runner.execute(request) shouldBe 2
        capture["error:${sourcePath}"] shouldContain "--dry-run and --execute are mutually exclusive"
    }

    test("F.6.e — --execute and --plan-only are mutually exclusive (exit 2)") {
        val (runner, capture) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = "db:postgres://localhost/test",
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            planOnly = true,
            report = tmpDir.resolve("exec-plan.report.json"),
        )
        runner.execute(request) shouldBe 2
        capture["error:${sourcePath}"] shouldContain "--execute and --plan-only are mutually exclusive"
    }

    test("F.6.e — --execute against a file target is rejected with exit 2") {
        val (runner, capture) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            execute = true,
            report = tmpDir.resolve("exec-file.report.json"),
        )
        runner.execute(request) shouldBe 2
        capture["error:${targetPath}"] shouldContain "--execute requires a DB target"
    }

    test("F.6.e — Up-SQL is echoed to stdout when --output is not set (no implicit file-write)") {
        val (runner, capture) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            // no --output, no --report, no --plan-only
        )
        runner.execute(request) shouldBe 0
        // Positive: Up-SQL hit stdout verbatim
        capture["stdout"] shouldContain "CREATE TABLE x (id INT);"
        // Invariant: no atomicWriter calls (no implicit Up sidecar, no
        // implicit Report sidecar)
        capture.keys.none { it.startsWith("wrote:") } shouldBe true
    }

    test("F.6.e — without --report, NO implicit report sidecar is written next to --output") {
        val (runner, capture) = captureRunner()
        val outPath = tmpDir.resolve("up-no-implicit.sql")
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            output = outPath,
            // explicitly NO --report
        )
        runner.execute(request) shouldBe 0
        // The Up-SQL artefact was finalised
        capture.containsKey("wrote:$outPath") shouldBe true
        // But NO sidecar at any of the candidate default locations
        for (candidate in listOf(
            tmpDir.resolve("up-no-implicit.sql.report.json"),
            tmpDir.resolve("up-no-implicit.report.json"),
            tmpDir.resolve("source.yaml.report.json"),
            tmpDir.resolve("source.report.json"),
            tmpDir.resolve("report.json"),
        )) {
            candidate.exists() shouldBe false
            capture.containsKey("wrote:$candidate") shouldBe false
        }
        // And report content was NOT echoed to stdout either — only
        // the success-non-plan-only path runs here, which writes
        // Up-SQL but not a report payload.
        val stdout = capture["stdout"] ?: ""
        stdout.contains("\"status\"") shouldBe false
    }

    test("F.6.e — --report path that collides with source/target is rejected exit 2") {
        val (runner, capture) = captureRunner()
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
            report = sourcePath, // collides with source operand path
        )
        runner.execute(request) shouldBe 2
        // Source file MUST remain unchanged — collision check fires
        // BEFORE any write attempt.
        Files.readString(sourcePath) shouldBe "# source"
        capture.keys.none { it.startsWith("wrote:") } shouldBe true
    }

    test("F.6.e — invalid operand syntax (empty db: source) yields exit 2") {
        val (runner, capture) = captureRunner()
        val request = SchemaMigrateRequest(
            source = "db:",
            target = targetPath.toString(),
            dialect = DatabaseDialect.POSTGRESQL,
        )
        runner.execute(request) shouldBe 2
        // operand-parse error is reported against the raw source ref
        capture["error:db:"] shouldContain "Invalid operand"
    }
})
