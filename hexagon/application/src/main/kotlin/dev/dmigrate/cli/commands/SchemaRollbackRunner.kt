package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.migration.MigrationFingerprint
import dev.dmigrate.core.diff.routine.RoutineBodyLogRedactor
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope
import java.nio.file.Files
import java.nio.file.Path

/**
 * Runner for `schema rollback` per `spec/cli-spec.md §6.1`.
 *
 * E.5 first-slice scope:
 *
 * 1. Read and parse the `--source` artefact via [RollbackArtefactParser]
 *    — strict JSON, hash-verified, single block, fixed format/version.
 * 2. With `--execute`:
 *    - Verify target dialect matches the artefact's `dialect` field
 *      (`TARGET_DIALECT_MISMATCH` → Exit 8).
 *    - Reject an artefact whose `fingerprintAlgorithm` differs from
 *      this build's; the stored fingerprint is not comparable
 *      (`ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH` → Exit 8).
 *    - Verify target current state matches `postUpFingerprint` (or
 *      one of `allowedPostUpFingerprints` for recovery artefacts)
 *      (`TARGET_STATE_MISMATCH` → Exit 8).
 *    - Reject destructive Down without `--allow-destructive` (Exit 8).
 *    - Reject partial rollback artefacts without
 *      `--allow-partial-rollback` before the first statement (Exit 8).
 *    - Run the SQL body via the injected executor; Exit 5 on
 *      execution error.
 *
 * Out of scope (Phase F):
 *
 * - Recovery rollback path (artefact authored with `recovery=true` is
 *   accepted for execute when state matches; recovery generation is
 *   on the `schema migrate --execute --generate-rollback` side and
 *   landed minimally in E.4).
 * - SQLite-Rebuild atomic-execution-unit guarantees (the executor is
 *   responsible for the transactional contract; this runner only
 *   relays Exit 5 on failure).
 */
class SchemaRollbackRunner(
    private val operandParser: (String) -> CompareOperand = CompareOperandParser::parse,
    private val dbLoader: ((CompareOperand.Database, Path?) -> ResolvedSchemaOperand)? = null,
    private val executor: ExecutorFn? = null,
    private val urlScrubber: (String) -> String = { it },
    private val fileReader: (Path) -> String = { Files.readString(it) },
    private val fingerprint: (dev.dmigrate.core.model.SchemaDefinition) -> String = MigrationFingerprint::compute,
    private val printError: (message: String, source: String) -> Unit,
) {
    private val userFacingErrors = UserFacingErrors(urlScrubber)
    private val userFacingPrintError = userFacingErrors.printError(printError)

    @Suppress("ReturnCount")
    fun execute(
        request: SchemaRollbackRequest,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): Int {
        cancellationToken.throwIfCancellationRequested()
        validateRequest(request)?.let { return it }

        val targetOp = parseTargetOperand(request) ?: return 2
        val parsed = readAndParseArtefact(request) ?: return 7

        if (!request.execute) return 0
        if (parsed.partialRollback && !request.allowPartialRollback) {
            userFacingPrintError(
                "Partial rollback artefact skips operations; pass --allow-partial-rollback to proceed.",
                request.source.toString(),
            )
            return 8
        }
        cancellationToken.throwIfCancellationRequested()
        return executeRollback(request, parsed, targetOp, cancellationToken)
    }

    @Suppress("ReturnCount")
    private fun executeRollback(
        request: SchemaRollbackRequest,
        parsed: ParsedRollbackArtefact,
        targetOp: CompareOperand.Database,
        cancellationToken: CancellationToken,
    ): Int {
        val loader = dbLoader ?: run {
            userFacingPrintError("--execute requires a DB loader to be wired.", request.target)
            return 2
        }
        val targetResolved = loadTarget(request, loader, targetOp) ?: return lastExitCode
        verifyTargetMatchesArtefact(request, parsed, targetResolved)?.let { return it }
        if (parsed.risk.destructive && !request.allowDestructive) {
            userFacingPrintError(
                "Down-SQL is destructive; pass --allow-destructive to proceed.",
                request.target,
            )
            return 8
        }
        val exec = executor ?: run {
            userFacingPrintError("--execute requires an executor to be wired.", request.target)
            return 2
        }
        cancellationToken.throwIfCancellationRequested()
        return runStatement(request, parsed, targetOp, exec)
    }

    private fun parseTargetOperand(request: SchemaRollbackRequest): CompareOperand.Database? {
        val parsed = try {
            operandParser(request.target)
        } catch (e: IllegalArgumentException) {
            userFacingPrintError("Invalid target: ${e.message}", request.target)
            return null
        }
        if (parsed !is CompareOperand.Database) {
            userFacingPrintError("--target must be a DB connection (db:<url>).", request.target)
            return null
        }
        return parsed
    }

    private fun readAndParseArtefact(request: SchemaRollbackRequest): ParsedRollbackArtefact? {
        val text = try {
            fileReader(request.source)
        } catch (e: Exception) {
            userFacingPrintError("Failed to read artefact: ${e.message}", request.source.toString())
            return null
        }
        return when (val r = RollbackArtefactParser.parse(text)) {
            is RollbackArtefactParser.Result.Success -> r.parsed
            is RollbackArtefactParser.Result.Failure -> {
                userFacingPrintError("Artefact invalid (${r.code}): ${r.message}", request.source.toString())
                null
            }
        }
    }

    private var lastExitCode: Int = 7

    private fun loadTarget(
        request: SchemaRollbackRequest,
        loader: (CompareOperand.Database, Path?) -> ResolvedSchemaOperand,
        targetOp: CompareOperand.Database,
    ): ResolvedSchemaOperand? = try {
        loader(targetOp, request.cliConfigPath)
    } catch (e: CompareConfigException) {
        userFacingPrintError("Config/URL error: ${e.message}", request.target)
        lastExitCode = 7
        null
    } catch (e: Exception) {
        userFacingPrintError("Connection/metadata error: ${e.message}", request.target)
        lastExitCode = 4
        null
    }

    private fun verifyTargetMatchesArtefact(
        request: SchemaRollbackRequest,
        parsed: ParsedRollbackArtefact,
        targetResolved: ResolvedSchemaOperand,
    ): Int? {
        targetResolved.dialect?.let { connDialect ->
            if (connDialect.name != parsed.dialect) {
                userFacingPrintError(
                    "TARGET_DIALECT_MISMATCH: artefact targets `${parsed.dialect}` but connection is `${connDialect.name}`.",
                    request.target,
                )
                return 8
            }
        }
        // No CompareOperandNormalizer call on `targetResolved.schema` here:
        // a live-DB schema comes straight from the reader, so it cannot
        // carry user-authored markers needing validation. The fingerprint
        // is also content-only, so any prefix in the synthetic schema name
        // is irrelevant. Keep this in sync with
        // SchemaMigrateRunner.runPostCompare's normalizer call, which only
        // exists to surface malformed-marker errors from any *file*-side
        // operand the loader might return.
        // Guard the stored fingerprint against an algorithm-version bump: the artefact's
        // postUpFingerprint was computed with `parsed.fingerprintAlgorithm`, but this build
        // recomputes the target with MigrationFingerprint.ALGORITHM. When they differ the two
        // strings are not comparable, so surface a precise "regenerate the artefact" error
        // rather than a misleading TARGET_STATE_MISMATCH on an otherwise-correct target.
        if (parsed.fingerprintAlgorithm != MigrationFingerprint.ALGORITHM) {
            // A legitimately-built artefact always carries a non-blank algorithm; render a
            // blank one (only reachable via a tampered/hand-edited header) as `(none)` rather
            // than empty backticks.
            val artefactAlgorithm = parsed.fingerprintAlgorithm.ifBlank { "(none)" }
            userFacingPrintError(
                "ROLLBACK_FINGERPRINT_ALGORITHM_MISMATCH: artefact was produced with fingerprint " +
                    "algorithm `$artefactAlgorithm`, but this build computes " +
                    "`${MigrationFingerprint.ALGORITHM}`. The stored target fingerprint is not comparable " +
                    "across algorithm versions; regenerate the rollback artefact with this version of the tool.",
                request.target,
            )
            return 8
        }
        val targetFingerprint = fingerprint(targetResolved.schema)
        val acceptable = if (parsed.recovery) {
            parsed.allowedPostUpFingerprints.orEmpty().toSet()
        } else {
            setOf(parsed.postUpFingerprint)
        }
        if (targetFingerprint !in acceptable) {
            userFacingPrintError(
                "TARGET_STATE_MISMATCH: target fingerprint `$targetFingerprint` not in expected set.",
                request.target,
            )
            return 8
        }
        return null
    }

    private fun runStatement(
        request: SchemaRollbackRequest,
        parsed: ParsedRollbackArtefact,
        targetOp: CompareOperand.Database,
        exec: ExecutorFn,
    ): Int {
        val statements = if (parsed.statementIndex.isNotEmpty()) {
            parsed.statementsFromIndex()
        } else {
            splitLegacyArtefactBody(
                sqlBody = parsed.sqlBody,
                operationIds = parsed.operationIds.toSet(),
                destructive = parsed.risk.destructive,
            )
        }
        val trace = try {
            exec(targetOp, statements, request.cliConfigPath)
        } catch (e: Exception) {
            // E.1 Slice F.1: JDBC driver error messages routinely quote
            // statement fragments; for routine Down-SQL that risks
            // leaking the routine body to stderr. Rollback has no
            // `--debug-body` escape, so always redact.
            val rawMessage = e.message ?: e::class.simpleName
            val redacted = RoutineBodyLogRedactor.redact(rawMessage)
            userFacingPrintError(
                "Down execution failed: $redacted",
                request.target,
            )
            return 5
        }
        return if (trace.executionError != null) {
            // Defensive: the injected executor may not redact, so
            // re-route the trace message through the central redactor
            // before printing.
            val redacted = RoutineBodyLogRedactor.redact(trace.executionError)
            userFacingPrintError("Down execution error: $redacted", request.target)
            5
        } else {
            0
        }
    }

    /**
     * Legacy v1 fallback: split the artefact's `sqlBody` back into individual
     * [MigrationDdlStatement]s. The builder ([RollbackArtefactBuilder.canonicalBody])
     * joins per-statement SQL with `\n\n`, so we reverse that exact
     * separator. JDBC drivers (e.g. xerial-sqlite) do NOT execute
     * multi-statement strings via `Statement.execute(...)` — they
     * silently truncate at the first `;` — so handing the executor a
     * single bundled statement skips everything past the first one
     * and was the F.4 forcing function for this fix.
     *
     * Phase tagging: the artefact format does not preserve the
     * planner's per-statement [DiffPhase], so we stamp every split
     * statement with [DiffPhase.TABLES] as a generic "executable
     * body" placeholder.
     *
     * New v2 artefacts bypass this method completely: their validated
     * `statementIndex` carries byte ranges, hashes and [TransactionScope].
     * This method remains only as the controlled compatibility path for
     * pre-G.2 v1 artefacts.
     */
    private fun splitLegacyArtefactBody(
        sqlBody: String,
        operationIds: Set<String>,
        destructive: Boolean,
    ): List<MigrationDdlStatement> {
        val risk = if (destructive) {
            dev.dmigrate.core.diff.migration.OperationRisk(destructive = true)
        } else {
            dev.dmigrate.core.diff.migration.OperationRisk.SAFE
        }
        val splitSql = sqlBody.split("\n\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val inferredScope = if (splitSql.any { startsWithBeginToken(it) }) {
            TransactionScope.STREAM_OWNED
        } else {
            TransactionScope.RUNNER_OWNED
        }
        return splitSql.map { sql ->
            MigrationDdlStatement(
                sql = sql,
                operationIds = operationIds,
                risk = risk,
                phase = dev.dmigrate.core.diff.migration.DiffPhase.TABLES,
                transactionScope = inferredScope,
            )
        }
    }

    private fun startsWithBeginToken(sql: String): Boolean {
        val trimmed = sql.trimStart().uppercase()
        return trimmed.startsWith("BEGIN;") ||
            trimmed.startsWith("BEGIN ") ||
            trimmed == "BEGIN"
    }

    private fun validateRequest(request: SchemaRollbackRequest): Int? {
        if (request.execute && request.dryRun) {
            userFacingPrintError("--execute and --dry-run are mutually exclusive.", request.source.toString())
            return 2
        }
        return null
    }
}

// ── Request DTO ─────────────────────────────────────────────────────

data class SchemaRollbackRequest(
    val source: Path,
    val target: String,
    val execute: Boolean = false,
    val allowDestructive: Boolean = false,
    val allowPartialRollback: Boolean = false,
    val dryRun: Boolean = false,
    val cliConfigPath: Path? = null,
)
