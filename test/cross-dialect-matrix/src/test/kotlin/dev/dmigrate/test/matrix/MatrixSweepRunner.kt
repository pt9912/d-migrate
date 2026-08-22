package dev.dmigrate.test.matrix

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.mysql.MysqlDiffDdlGenerator
import dev.dmigrate.driver.postgresql.PostgresDiffDdlGenerator
import dev.dmigrate.driver.sqlite.SqliteDiffDdlGenerator
import dev.dmigrate.format.SchemaFileResolver
import java.nio.file.Files
import java.nio.file.Path

/**
 * Executes one [MatrixCell] via [SchemaMigrateRunner] in file-mode
 * (no Testcontainers, no live DB probes). The runner is wired with
 * the real dialect renderers (`PostgresDiffDdlGenerator`,
 * `MysqlDiffDdlGenerator`, `SqliteDiffDdlGenerator`) so the matrix
 * detects regressions in actual dialect output, not just orchestration.
 *
 * Fixture layout (loaded from classpath via [MatrixFixtures]):
 *
 * ```
 * src/test/resources/fixtures/<workstream>/<dialect>/<kind>/
 *   ├── current.yaml      # source schema for the migrate run
 *   └── desired.yaml      # target schema for the migrate run
 * ```
 *
 * Tests assert `executeCell(cell) shouldBe cell.expectedExitCode`.
 */
internal class MatrixSweepRunner {

    private val validator = SchemaValidator()
    private val rendererFor: (DatabaseDialect) -> DiffDdlGenerator? = { dialect ->
        when (dialect) {
            DatabaseDialect.POSTGRESQL -> PostgresDiffDdlGenerator()
            DatabaseDialect.MYSQL -> MysqlDiffDdlGenerator()
            DatabaseDialect.SQLITE -> SqliteDiffDdlGenerator()
            // MSSQL tritt dem Sweep zusammen mit seinem Diff-Renderer bei
            // (docs/planning/in-progress/mssql-dialect-scoping.md, Slice 5):
            // ohne MssqlDiffDdlGenerator waere jede Zelle entweder ein
            // Wegwerf-Carve-out oder ein gepinnter Fehlerpfad. Bis dahin
            // null = derselbe "No renderer registered"-Pfad wie produktiv.
            DatabaseDialect.MSSQL -> null
        }
    }

    /**
     * Materialise the cell's fixture pair into temp files, build the
     * file-mode request, run, and return the result. Captures
     * stdout/stderr so the sweep does not pollute the test log, but
     * exposes them on the returned [Outcome] so a failing assertion
     * can surface the blocker reason.
     */
    fun executeCell(cell: MatrixCell): Outcome {
        val fixtures = MatrixFixtures.loadPair(cell)
        val tmp = Files.createTempDirectory("matrix-${cell.workstream}-")
        try {
            // `schema migrate` CLI semantics (spec/cli-spec.md §6.1):
            //   --source = Soll-Schema (desired)
            //   --target = Ist-Zustand (current)
            // Mapping the fixture's `desired.yaml` to source and
            // `current.yaml` to target so a "fixture transitions from
            // current into desired" reads naturally on disk but
            // arrives at the runner in the CLI-canonical orientation.
            val sourcePath = tmp.resolve("source.yaml")
            val targetPath = tmp.resolve("target.yaml")
            Files.writeString(sourcePath, fixtures.desiredYaml)
            Files.writeString(targetPath, fixtures.currentYaml)
            return runOnce(cell, sourcePath, targetPath)
        } finally {
            deleteRecursive(tmp)
        }
    }

    data class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        /**
         * Body of the emitted `*.rollback.sql` artefact; `null` for
         * non-ROLLBACK cells or when the runner did not produce a
         * rollback artefact (e.g. ROLLBACK_NOT_POSSIBLE blocker
         * surfaced before render).
         */
        val rollbackBody: String? = null,
    )

    private fun runOnce(cell: MatrixCell, sourcePath: Path, targetPath: Path): Outcome {
        val capturedStdout = StringBuilder()
        val capturedStderr = StringBuilder()
        var capturedRollback: String? = null

        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                val schema = SchemaFileResolver.codecForPath(op.path).read(op.path)
                ResolvedSchemaOperand(
                    reference = op.path.toString(),
                    schema = schema,
                    validation = validator.validate(schema),
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            rendererFor = rendererFor,
            // POSITIVE / BLOCKER / REPORT / FILE_MODE never inspect the
            // emitted artefacts; ROLLBACK captures the rollback artefact
            // body so a future cell-level assertion can pin its shape.
            atomicWriter = { path, body ->
                if (path.toString().endsWith(".rollback.sql")) {
                    capturedRollback = body
                }
            },
            renderReport = { report, _ ->
                // Minimal report rendering, but include enough
                // diagnostic surface (blockers + primaryBlockedReason)
                // so a failing cell's stdout dump tells the operator
                // exactly which planner/renderer code path fired.
                buildString {
                    append("{\"status\":\"").append(report.status).append('"')
                    append(",\"exitCode\":").append(report.exitCode)
                    val primary = report.summary.primaryBlockedReason ?: ""
                    append(",\"primaryBlockedReason\":\"").append(primary).append('"')
                    append(",\"blockers\":[")
                    report.blockers.joinToString(",") { b ->
                        "{\"reason\":\"${b.reason}\",\"diagnosticCodes\":[${b.diagnosticCodes.joinToString(",") { "\"$it\"" }}]}"
                    }.also { append(it) }
                    append("]")
                    append(",\"diagnostics\":[")
                    report.diagnostics.joinToString(",") { d ->
                        "{\"code\":\"${d.code}\",\"severity\":\"${d.severity}\",\"message\":\"${d.message.replace("\"", "\\\"")}\"}"
                    }.also { append(it) }
                    append("]")
                    append('}')
                }
            },
            printError = { msg, src -> capturedStderr.append("[$src] $msg\n") },
            stdout = { capturedStdout.append(it).append('\n') },
            stderr = { capturedStderr.append(it).append('\n') },
        )

        // Kind-specific request shaping. POSITIVE / BLOCKER stay
        // plan-only; REPORT inherits POSITIVE shape (the cell-side
        // assertion shifts to report fields, not request flags);
        // ROLLBACK flips `generateRollback` so the runner emits the
        // rollback artefact and the captured body becomes assertable;
        // FILE_MODE inherits POSITIVE shape (the axis label
        // documents intent — live-DB-shaped workstreams behave
        // benignly in file-mode — without changing the runner flags).
        val generateRollback = cell.kind == MatrixCell.Kind.ROLLBACK
        val request = SchemaMigrateRequest(
            source = sourcePath.toString(),
            target = targetPath.toString(),
            dialect = cell.dialect,
            planOnly = true,
            generateRollback = generateRollback,
        )

        val exit = runner.execute(request)
        return Outcome(
            exitCode = exit,
            stdout = capturedStdout.toString(),
            stderr = capturedStderr.toString(),
            rollbackBody = capturedRollback,
        )
    }

    private fun deleteRecursive(root: Path) {
        // Best-effort cleanup; a leaked temp dir is harmless and we
        // do not want the sweep to fail on cleanup-only IOExceptions.
        runCatching {
            Files.walk(root).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    runCatching { Files.deleteIfExists(path) }
                }
            }
        }
    }
}
