package dev.dmigrate.cli.commands

import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.util.UUID
import kotlin.io.path.writeText

/**
 * Carves the artefact-write concerns out of [SchemaMigrateRunner] so the
 * runner stays under Detekt's `LargeClass` budget.
 *
 * Owns the atomic-write semantics for the Up-SQL artefact, the
 * `--rollback-output` artefact, the recovery rollback artefact (Plan
 * §F.5.e/f/h), and the structured report. Tri-state side effects are
 * reported back to the runner via [RecoveryWriteOutcome] / nullable
 * return shapes so the runner can compute the correct exit code without
 * threading I/O state through the orchestrator.
 */
internal class SchemaMigrateArtefactSink(
    private val ensureParentDirectories: (Path) -> Unit,
    private val atomicWriter: (Path, String) -> Unit,
    private val stdout: (String) -> Unit,
    private val printError: (message: String, source: String) -> Unit,
    private val renderReport: (SchemaMigrateReport, format: String) -> String,
    private val clock: Clock,
) {

    /**
     * Writes (or stdout-echoes) the rendered Up-SQL when not in
     * `--execute` mode. Returns `null` on success or `7` on write
     * failure so callers can route through [emitReportAndExit] with
     * the proper exit code.
     */
    fun writeOrEchoUpSql(request: SchemaMigrateRequest, rendered: MigrationDdlResult): Int? {
        val body = rendered.statements.joinToString("\n\n") { it.sql }
        val upSql = renderSqlArtefactHeader(body) + body
        if (request.output == null) {
            stdout(upSql)
            return null
        }
        return try {
            ensureParentDirectories(request.output)
            atomicWriter(request.output, upSql)
            null
        } catch (e: Exception) {
            printError(
                "Failed to write up-SQL artefact: ${e.message}",
                request.output.toString(),
            )
            7
        }
    }

    /** Returns true on successful atomic write, false on failure. */
    fun writeRollbackArtefact(path: Path, artefact: String): Boolean = try {
        ensureParentDirectories(path)
        atomicWriter(path, artefact)
        true
    } catch (e: Exception) {
        printError(
            "Failed to write rollback artefact: ${e.message}",
            path.toString(),
        )
        false
    }

    /**
     * F.5.e/f/h: write a recovery rollback artefact to
     * `<--rollback-output>.recovery.<timestamp>.rollback.sql`. Tri-state
     * return: `null` (no attempt), [RecoveryWriteOutcome.Written], or
     * [RecoveryWriteOutcome.Failed]. The runner escalates exit code to
     * `7` only on [RecoveryWriteOutcome.Failed] per Plan §7.1.
     */
    fun tryWriteRecoveryArtefact(
        request: SchemaMigrateRequest,
        recoveryContext: RecoveryContext?,
        allowedFingerprint: String?,
        postUpVerified: Boolean,
    ): RecoveryWriteOutcome? {
        val ctx = recoveryContext ?: return null
        val fp = allowedFingerprint ?: return null
        val output = request.rollbackOutput ?: return null
        val recoveryPath = RecoveryArtefactPath.recoveryPathFor(output, clock.instant())
        val artefact = ctx.build(fp, postUpVerified)
        return try {
            ensureParentDirectories(recoveryPath)
            atomicWriter(recoveryPath, artefact)
            RecoveryWriteOutcome.Written
        } catch (e: Exception) {
            printError(
                "Failed to write recovery rollback artefact: ${e.message}",
                recoveryPath.toString(),
            )
            printError(
                "Up was executed against the target but no finalised rollback artefact " +
                    "could be written; manual database recovery may be required.",
                request.target,
            )
            RecoveryWriteOutcome.Failed
        }
    }

    /**
     * Single end-of-pipeline report sink. Injects [rollbackFinalized]
     * into the report's execution view (when present) and writes the
     * report atomically when `--report` is set, else echoes to stdout
     * for the blocker- and plan-only branches that were the only ones
     * doing stdout-echo before the F.5.c restructuring.
     */
    fun emitReportAndExit(
        request: SchemaMigrateRequest,
        report: SchemaMigrateReport,
        rollbackFinalized: Boolean?,
        baseExit: Int,
    ): Int {
        val finalReport = if (report.execution == null) {
            report
        } else {
            report.copy(execution = report.execution.copy(rollbackFinalized = rollbackFinalized))
        }
        request.report?.let { reportPath ->
            if (writeReport(reportPath, finalReport, request.reportFormat) == null) return 7
        }
        if (request.report == null && (baseExit == 8 || request.planOnly)) {
            stdout(renderReport(finalReport, request.reportFormat))
        }
        return baseExit
    }

    fun writeReport(path: Path, report: SchemaMigrateReport, format: String): Unit? = try {
        ensureParentDirectories(path)
        atomicWriter(path, renderReport(report, format))
        Unit
    } catch (e: Exception) {
        printError("Failed to write report: ${e.message}", path.toString())
        null
    }

    /**
     * Phase H header: warns operators about the two standalone-path
     * limitations of SQLite-Rebuild streams. Empty string when the
     * stream contains no SQLite-Rebuild sequences.
     */
    private fun renderSqlArtefactHeader(body: String): String {
        if (!body.contains("__dmg_rebuild_")) return ""
        return buildString {
            append("-- d-migrate schema migrate --plan-only artefact\n")
            append("-- \n")
            append("-- SQLite-Rebuild caveats for standalone execution:\n")
            append("-- \n")
            append("--   1. Temp-Name collision probe is against the schema-model only.\n")
            append("--      Ad-hoc objects in the live DB (CREATE INDEX outside the\n")
            append("--      schema, sqlite_stat* tables, etc.) may collide at execute-\n")
            append("--      time. Run `schema migrate --execute` against the target to\n")
            append("--      engage the live `sqlite_master` probe (Plan-2 §A.2), which\n")
            append("--      unions the live catalog with the schema snapshot before\n")
            append("--      temp-name resolution.\n")
            append("-- \n")
            append("--   2. The rebuild ends with `PRAGMA foreign_keys = ON;`. If the\n")
            append("--      prior state was OFF, it is NOT restored — use the d-migrate\n")
            append("--      runner (`schema migrate --execute`) for Round-Trip-State-Compat.\n")
            append("-- \n")
            append("-- See docs/planning/done/diffresult-migration-plan.md §H for\n")
            append("-- the full contract.\n")
            append("\n")
        }
    }

}

/**
 * Default atomic writer: stages content via a `<path>.tmp-<uuid>`
 * sibling and then renames atomically. Failure modes leave the
 * original file unchanged.
 */
internal fun defaultAtomicWriter(path: Path, content: String) {
    val tmp = path.resolveSibling("${path.fileName}.tmp-${UUID.randomUUID()}")
    tmp.writeText(content)
    try {
        Files.move(
            tmp,
            path,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        throw e
    }
}
