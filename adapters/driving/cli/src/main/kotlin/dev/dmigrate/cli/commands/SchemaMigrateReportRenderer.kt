package dev.dmigrate.cli.commands

/**
 * Minimal JSON / YAML renderer for [SchemaMigrateReport]. The
 * format string is one of `"json"` (default) or `"yaml"`. The
 * implementation is deliberately small — no third-party JSON or
 * YAML dependency — and hand-tuned to the report's flat shape.
 */
internal object SchemaMigrateReportRenderer {

    fun render(report: SchemaMigrateReport, format: String): String = when (format.lowercase()) {
        "yaml" -> renderYaml(report)
        else -> renderJson(report)
    }

    private fun renderJson(report: SchemaMigrateReport): String {
        val sb = StringBuilder()
        sb.append("{\n")
        appendField(sb, "status", jsonString(report.status), indent = 1)
        appendField(sb, "exitCode", report.exitCode.toString(), indent = 1)
        appendField(sb, "source", jsonString(report.source), indent = 1)
        appendField(sb, "target", jsonString(report.target), indent = 1)
        appendField(sb, "dialect", jsonString(report.dialect), indent = 1)
        appendField(sb, "planOnly", report.planOnly.toString(), indent = 1)
        appendField(sb, "blockers", renderBlockers(report.blockers), indent = 1)
        appendField(sb, "diagnostics", renderDiagnostics(report.diagnostics), indent = 1)
        appendField(sb, "materializedViews", renderMaterializedViews(report.materializedViews), indent = 1)
        appendField(sb, "overlays", renderOverlays(report.overlays), indent = 1)
        appendField(
            sb,
            "sqliteCastPreflights",
            SchemaMigratePreflightRenderers.renderSqliteCastPreflights(report.sqliteCastPreflights),
            indent = 1,
        )
        appendField(
            sb,
            "mysqlSequenceCanonicity",
            SchemaMigratePreflightRenderers.renderMysqlSequenceCanonicity(report.mysqlSequenceCanonicity),
            indent = 1,
        )
        appendField(sb, "summary", renderSummary(report.summary), indent = 1)
        appendField(sb, "bodyDisplay", jsonString(report.bodyDisplay.name), indent = 1)
        appendField(sb, "bodyEmbedding", renderBodyEmbedding(report.bodyEmbedding), indent = 1)
        report.execution?.let { appendField(sb, "execution", renderExecution(it), indent = 1) }
        appendField(sb, "operations", renderOperations(report.operations), indent = 1)
        val stmts = report.statements
        if (stmts != null) {
            appendField(sb, "statements", renderStatements(stmts), indent = 1, last = true)
        } else {
            appendField(sb, "statements", "null", indent = 1, last = true)
        }
        sb.append("}\n")
        return sb.toString()
    }

    private fun renderYaml(report: SchemaMigrateReport): String {
        val sb = StringBuilder()
        appendYamlHeader(sb, report)
        appendYamlSummary(sb, report.summary)
        sb.append("blockers:").append(if (report.blockers.isEmpty()) " []\n" else "\n")
        for (b in report.blockers) {
            sb.append("  - reason: ").append(b.reason).append('\n')
            sb.append("    operationIds: ").append(yamlList(b.operationIds)).append('\n')
            sb.append("    diagnosticCodes: ").append(yamlList(b.diagnosticCodes)).append('\n')
        }
        sb.append("diagnostics:").append(if (report.diagnostics.isEmpty()) " []\n" else "\n")
        for (d in report.diagnostics) {
            sb.append("  - code: ").append(d.code).append('\n')
            sb.append("    severity: ").append(d.severity).append('\n')
            sb.append("    message: ").append(yamlString(d.message)).append('\n')
            d.operationId?.let { sb.append("    operationId: ").append(it).append('\n') }
        }
        sb.append("materializedViews:").append(if (report.materializedViews.isEmpty()) " []\n" else "\n")
        for (mv in report.materializedViews) {
            sb.append("  - operationId: ").append(mv.operationId).append('\n')
            sb.append("    action: ").append(mv.action).append('\n')
            sb.append("    path: ").append(yamlList(mv.path)).append('\n')
            sb.append("    dialect: ").append(mv.dialect).append('\n')
            sb.append("    status: ").append(mv.status).append('\n')
            mv.primaryBlockedReason?.let {
                sb.append("    primaryBlockedReason: ").append(it).append('\n')
            }
            sb.append("    stalenessAfterUp: ").append(mv.stalenessAfterUp).append('\n')
            sb.append("    refreshSteps: ").append(yamlList(mv.refreshSteps)).append('\n')
            sb.append("    locking: ").append(mv.locking).append('\n')
            sb.append("    rollback: ").append(mv.rollback).append('\n')
            if (mv.dependencyBlockers.isNotEmpty()) {
                sb.append("    dependencyBlockers:\n")
                for (blocker in mv.dependencyBlockers) {
                    sb.append("      - droppingOperationId: ").append(blocker.droppingOperationId).append('\n')
                    sb.append("        droppingPath: ").append(yamlList(blocker.droppingPath)).append('\n')
                    sb.append("        droppingKind: ").append(blocker.droppingKind).append('\n')
                }
            }
        }
        sb.append("overlays:").append(if (report.overlays.isEmpty()) " []\n" else "\n")
        for (overlay in report.overlays) {
            sb.append("  - source: ").append(yamlString(overlay.source)).append('\n')
            overlay.entryId?.let { sb.append("    entryId: ").append(yamlString(it)).append('\n') }
            sb.append("    overlayHash: ").append(yamlString(overlay.overlayHash)).append('\n')
            sb.append("    diagnosticCode: ").append(overlay.diagnosticCode).append('\n')
            sb.append("    severity: ").append(overlay.severity).append('\n')
        }
        sb.append("sqliteCastPreflights:").append(if (report.sqliteCastPreflights.isEmpty()) " []\n" else "\n")
        for (preflight in report.sqliteCastPreflights) {
            sb.append("  - operationId: ").append(yamlString(preflight.operationId)).append('\n')
            sb.append("    dialect: ").append(preflight.dialect).append('\n')
            sb.append("    table: ").append(yamlString(preflight.table)).append('\n')
            sb.append("    column: ").append(yamlString(preflight.column)).append('\n')
            sb.append("    sourceType: ").append(yamlString(preflight.sourceType)).append('\n')
            sb.append("    targetType: ").append(yamlString(preflight.targetType)).append('\n')
            sb.append("    status: ").append(preflight.status).append('\n')
            sb.append("    sqlHash: ").append(yamlString(preflight.sqlHash)).append('\n')
            sb.append("    totalRows: ").append(yamlOptional(preflight.totalRows?.toString())).append('\n')
            sb.append("    failingRows: ").append(yamlOptional(preflight.failingRows?.toString())).append('\n')
            sb.append("    sampleRowIds: ").append(yamlList(preflight.sampleRowIds)).append('\n')
            sb.append("    problem: ").append(yamlOptional(preflight.problem)).append('\n')
        }
        sb.append("mysqlSequenceCanonicity:")
            .append(if (report.mysqlSequenceCanonicity.isEmpty()) " []\n" else "\n")
        for (declaration in report.mysqlSequenceCanonicity) {
            sb.append("  - operationId: ").append(yamlString(declaration.operationId)).append('\n')
            sb.append("    dialect: ").append(declaration.dialect).append('\n')
            sb.append("    kind: ").append(declaration.kind).append('\n')
            sb.append("    objectName: ").append(yamlString(declaration.objectName)).append('\n')
            sb.append("    status: ").append(declaration.status).append('\n')
            sb.append("    sqlHash: ").append(yamlString(declaration.sqlHash)).append('\n')
            sb.append("    driftField: ").append(yamlOptional(declaration.driftField)).append('\n')
            sb.append("    expected: ").append(yamlOptional(declaration.expected)).append('\n')
            sb.append("    actual: ").append(yamlOptional(declaration.actual)).append('\n')
            sb.append("    problem: ").append(yamlOptional(declaration.problem)).append('\n')
        }
        report.execution?.let {
            sb.append("execution:\n")
            sb.append("  started: ").append(it.started).append('\n')
            sb.append("  completed: ").append(it.completed).append('\n')
            sb.append("  statementsAttempted: ").append(it.statementsAttempted).append('\n')
            sb.append("  transactionRolledBack: ").append(it.transactionRolledBack).append('\n')
            sb.append("  sideEffectsPossible: ").append(it.sideEffectsPossible).append('\n')
            it.executionError?.let { e -> sb.append("  executionError: ").append(yamlString(e)).append('\n') }
            sb.append("  recoverability: ").append(yamlOptional(it.recoverability)).append('\n')
            sb.append("  statementGroups:").append(if (it.statementGroups.isEmpty()) " []\n" else "\n")
            for (group in it.statementGroups) {
                sb.append("    - statementGroupId: ").append(yamlString(group.statementGroupId)).append('\n')
                sb.append("      operationIds: ").append(yamlList(group.operationIds)).append('\n')
                sb.append("      statementStartInclusive: ").append(group.statementStartInclusive).append('\n')
                sb.append("      statementEndExclusive: ").append(group.statementEndExclusive).append('\n')
                sb.append("      transactionScope: ").append(group.transactionScope).append('\n')
                sb.append("      transactionBoundary: ").append(group.transactionBoundary).append('\n')
            }
        }
        return sb.toString()
    }

    private fun appendYamlHeader(sb: StringBuilder, report: SchemaMigrateReport) {
        sb.append("status: ").append(yamlString(report.status)).append('\n')
        sb.append("exitCode: ").append(report.exitCode).append('\n')
        sb.append("source: ").append(yamlString(report.source)).append('\n')
        sb.append("target: ").append(yamlString(report.target)).append('\n')
        sb.append("dialect: ").append(report.dialect).append('\n')
        sb.append("planOnly: ").append(report.planOnly).append('\n')
        sb.append("bodyDisplay: ").append(report.bodyDisplay.name).append('\n')
        sb.append("bodyEmbedding:\n")
        sb.append("  status: ").append(report.bodyEmbedding.status.name).append('\n')
        sb.append("  version: ").append(yamlString(report.bodyEmbedding.version)).append('\n')
        sb.append("  source: ").append(report.bodyEmbedding.source.name).append('\n')
        report.bodyEmbedding.reason?.let { sb.append("  reason: ").append(yamlString(it)).append('\n') }
    }

    private fun appendYamlSummary(sb: StringBuilder, summary: SchemaMigrateSummary) {
        sb.append("summary:\n")
        with(summary) {
            sb.append("  operationsTotal: ").append(operationsTotal).append('\n')
            sb.append("  operationsRendered: ").append(operationsRendered).append('\n')
            sb.append("  operationsSkipped: ").append(operationsSkipped).append('\n')
            sb.append("  statementsTotal: ").append(statementsTotal).append('\n')
            sb.append("  destructiveCount: ").append(destructiveCount).append('\n')
            sb.append("  manualActionCount: ").append(manualActionCount).append('\n')
            sb.append("  nonReversibleCount: ").append(nonReversibleCount).append('\n')
            sb.append("  primaryBlockedReason: ").append(yamlOptional(primaryBlockedReason)).append('\n')
            sb.append("  downStatementsTotal: ").append(yamlOptional(downStatementsTotal?.toString())).append('\n')
            sb.append("  downBlocked: ").append(downBlocked).append('\n')
            sb.append("  planHasImplicitCommitDdl: ").append(planHasImplicitCommitDdl).append('\n')
            sb.append("  planFullyRollbackable: ").append(planFullyRollbackable).append('\n')
            sb.append("  planRequiresExclusiveAccess: ").append(planRequiresExclusiveAccess).append('\n')
            sb.append("  catalogProbeMode: ").append(catalogProbeMode).append('\n')
            sb.append("  spatialProfile: ").append(yamlOptional(spatialProfile)).append('\n')
            sb.append("  requiredExtensions: ").append(yamlList(requiredExtensions)).append('\n')
            sb.append("  verifiedExtensions: ").append(yamlList(verifiedExtensions)).append('\n')
            sb.append("  missingExtensions: ").append(yamlList(missingExtensions)).append('\n')
            sb.append("  extensionInstallStatements: ").append(yamlList(extensionInstallStatements)).append('\n')
        }
    }

    // ── JSON helpers ───────────────────────────────────────────────

    private fun appendField(sb: StringBuilder, key: String, value: String, indent: Int, last: Boolean = false) {
        sb.append("  ".repeat(indent))
        sb.append('"').append(key).append("\": ").append(value)
        sb.append(if (last) "\n" else ",\n")
    }

    private fun renderBlockers(blockers: List<SchemaMigrateBlockerView>): String =
        blockers.joinToString(prefix = "[", postfix = "]", separator = ",") { b ->
            "{\"reason\":${jsonString(b.reason)}," +
                "\"operationIds\":${jsonStringArray(b.operationIds)}," +
                "\"diagnosticCodes\":${jsonStringArray(b.diagnosticCodes)}}"
        }

    private fun renderDiagnostics(diags: List<SchemaMigrateDiagnosticView>): String =
        diags.joinToString(prefix = "[", postfix = "]", separator = ",") { d ->
            val opId = d.operationId?.let { ",\"operationId\":${jsonString(it)}" } ?: ""
            "{\"code\":${jsonString(d.code)},\"severity\":${jsonString(d.severity)}," +
                "\"message\":${jsonString(d.message)}$opId}"
        }

    private fun renderMaterializedViews(views: List<SchemaMigrateMaterializedViewContractView>): String =
        views.joinToString(prefix = "[", postfix = "]", separator = ",") { v ->
            val primary = v.primaryBlockedReason?.let { ",\"primaryBlockedReason\":${jsonString(it)}" } ?: ""
            val deps = if (v.dependencyBlockers.isEmpty()) "" else ",\"dependencyBlockers\":" +
                v.dependencyBlockers.joinToString(prefix = "[", postfix = "]", separator = ",") { b ->
                    "{\"droppingOperationId\":${jsonString(b.droppingOperationId)}," +
                        "\"droppingPath\":${jsonStringArray(b.droppingPath)}," +
                        "\"droppingKind\":${jsonString(b.droppingKind)}}"
                }
            "{\"operationId\":${jsonString(v.operationId)},\"action\":${jsonString(v.action)}," +
                "\"path\":${jsonStringArray(v.path)},\"dialect\":${jsonString(v.dialect)}," +
                "\"status\":${jsonString(v.status)}$primary," +
                "\"stalenessAfterUp\":${jsonString(v.stalenessAfterUp)}," +
                "\"refreshSteps\":${jsonStringArray(v.refreshSteps)}," +
                "\"locking\":${jsonString(v.locking)},\"rollback\":${jsonString(v.rollback)}$deps}"
        }

    private fun renderOverlays(overlays: List<SchemaMigrateOverlayView>): String =
        overlays.joinToString(prefix = "[", postfix = "]", separator = ",") { overlay ->
            val entryId = overlay.entryId?.let { ",\"entryId\":${jsonString(it)}" } ?: ""
            "{\"source\":${jsonString(overlay.source)}$entryId," +
                "\"overlayHash\":${jsonString(overlay.overlayHash)}," +
                "\"diagnosticCode\":${jsonString(overlay.diagnosticCode)}," +
                "\"severity\":${jsonString(overlay.severity)}}"
        }

    // `renderSqliteCastPreflights` + `renderMysqlSequenceCanonicity`
    // live in `SchemaMigratePreflightRenderers` (split out to keep
    // this object under Detekt's TooManyFunctions budget).

    private fun renderSummary(s: SchemaMigrateSummary): String = buildString {
        append('{')
        append("\"operationsTotal\":${s.operationsTotal},")
        append("\"operationsRendered\":${s.operationsRendered},")
        append("\"operationsSkipped\":${s.operationsSkipped},")
        append("\"statementsTotal\":${s.statementsTotal},")
        append("\"destructiveCount\":${s.destructiveCount},")
        append("\"manualActionCount\":${s.manualActionCount},")
        append("\"nonReversibleCount\":${s.nonReversibleCount},")
        append("\"primaryBlockedReason\":${jsonOptString(s.primaryBlockedReason)},")
        append("\"downStatementsTotal\":${s.downStatementsTotal ?: "null"},")
        append("\"downBlocked\":${s.downBlocked},")
        append("\"planHasImplicitCommitDdl\":${s.planHasImplicitCommitDdl},")
        append("\"planFullyRollbackable\":${s.planFullyRollbackable},")
        append("\"planRequiresExclusiveAccess\":${s.planRequiresExclusiveAccess},")
        append("\"catalogProbeMode\":${jsonString(s.catalogProbeMode)},")
        append("\"spatialProfile\":${jsonOptString(s.spatialProfile)},")
        append("\"requiredExtensions\":${jsonStringArray(s.requiredExtensions)},")
        append("\"verifiedExtensions\":${jsonStringArray(s.verifiedExtensions)},")
        append("\"missingExtensions\":${jsonStringArray(s.missingExtensions)},")
        append("\"extensionInstallStatements\":${jsonStringArray(s.extensionInstallStatements)}")
        append('}')
    }

    private fun renderExecution(e: SchemaMigrateExecutionView): String = buildString {
        append('{')
        append("\"started\":${e.started},")
        append("\"completed\":${e.completed},")
        append("\"statementsAttempted\":${e.statementsAttempted},")
        append("\"lastStatementOperationIds\":${jsonStringArray(e.lastStatementOperationIds)},")
        append("\"transactionRolledBack\":${e.transactionRolledBack},")
        append("\"sideEffectsPossible\":${e.sideEffectsPossible},")
        append("\"executionError\":${jsonOptString(e.executionError)},")
        append("\"recoverability\":${jsonOptString(e.recoverability)},")
        append("\"statementGroups\":${renderStatementGroups(e.statementGroups)}")
        append('}')
    }

    private fun renderStatementGroups(groups: List<SchemaMigrateStatementGroupView>): String =
        groups.joinToString(prefix = "[", postfix = "]", separator = ",") { group ->
            "{\"statementGroupId\":${jsonString(group.statementGroupId)}," +
                "\"operationIds\":${jsonStringArray(group.operationIds)}," +
                "\"statementStartInclusive\":${group.statementStartInclusive}," +
                "\"statementEndExclusive\":${group.statementEndExclusive}," +
                "\"transactionScope\":${jsonString(group.transactionScope)}," +
                "\"transactionBoundary\":${jsonString(group.transactionBoundary)}}"
        }

    private fun renderOperations(ops: List<SchemaMigrateOperationView>): String =
        ops.joinToString(prefix = "[", postfix = "]", separator = ",") { o ->
            "{\"id\":${jsonString(o.id)},\"kind\":${jsonString(o.kind)}," +
                "\"objectType\":${jsonString(o.objectType)},\"path\":${jsonStringArray(o.path)}," +
                "\"phase\":${jsonString(o.phase)},\"reversibility\":${jsonString(o.reversibility)}," +
                "\"rendered\":${o.rendered},\"skipped\":${o.skipped}}"
        }

    private fun renderStatements(stmts: List<SchemaMigrateStatementView>): String =
        stmts.joinToString(prefix = "[", postfix = "]", separator = ",") { s ->
            "{\"sql\":${jsonString(s.sql)},\"operationIds\":${jsonStringArray(s.operationIds)}," +
                "\"phase\":${jsonString(s.phase)},\"destructive\":${s.destructive}," +
                "\"sqlHash\":${jsonString(s.sqlHash)},\"sqlLength\":${s.sqlLength}," +
                "\"scrubbedPreview\":${jsonString(s.scrubbedPreview)}," +
                "\"scrubbingApplied\":${s.scrubbingApplied}}"
        }

    private fun renderBodyEmbedding(be: dev.dmigrate.driver.BodyEmbedding): String = buildString {
        append("{\"status\":").append(jsonString(be.status.name))
        append(",\"version\":").append(jsonString(be.version))
        append(",\"source\":").append(jsonString(be.source.name))
        append(",\"reason\":").append(jsonOptString(be.reason))
        append("}")
    }

    private fun jsonStringArray(elements: List<String>): String =
        elements.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }

    private fun jsonOptString(s: String?): String = if (s == null) "null" else jsonString(s)

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    // ── YAML helpers ───────────────────────────────────────────────

    private fun yamlString(s: String): String =
        if (needsYamlQuoting(s)) "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\"" else s

    private fun needsYamlQuoting(s: String): Boolean {
        if (s.contains(':') || s.contains('\n') || s.contains('#')) return true
        return s.startsWith(' ') || s.startsWith('\"')
    }

    private fun yamlOptional(s: String?): String = if (s == null) "null" else yamlString(s)

    private fun yamlList(elements: List<String>): String =
        elements.joinToString(prefix = "[", postfix = "]", separator = ", ") { yamlString(it) }
}
