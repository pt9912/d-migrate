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
        appendField(sb, "summary", renderSummary(report.summary), indent = 1)
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
        sb.append("status: ").append(yamlString(report.status)).append('\n')
        sb.append("exitCode: ").append(report.exitCode).append('\n')
        sb.append("source: ").append(yamlString(report.source)).append('\n')
        sb.append("target: ").append(yamlString(report.target)).append('\n')
        sb.append("dialect: ").append(report.dialect).append('\n')
        sb.append("planOnly: ").append(report.planOnly).append('\n')
        sb.append("summary:\n")
        with(report.summary) {
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
        }
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
        report.execution?.let {
            sb.append("execution:\n")
            sb.append("  started: ").append(it.started).append('\n')
            sb.append("  completed: ").append(it.completed).append('\n')
            sb.append("  statementsAttempted: ").append(it.statementsAttempted).append('\n')
            sb.append("  transactionRolledBack: ").append(it.transactionRolledBack).append('\n')
            sb.append("  sideEffectsPossible: ").append(it.sideEffectsPossible).append('\n')
            it.executionError?.let { e -> sb.append("  executionError: ").append(yamlString(e)).append('\n') }
        }
        return sb.toString()
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
        append("\"planRequiresExclusiveAccess\":${s.planRequiresExclusiveAccess}")
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
        append("\"executionError\":${jsonOptString(e.executionError)}")
        append('}')
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
                "\"phase\":${jsonString(s.phase)},\"destructive\":${s.destructive}}"
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
        if (s.contains(':') || s.contains('\n')) return true
        return s.startsWith(' ') || s.startsWith('\"')
    }

    private fun yamlOptional(s: String?): String = if (s == null) "null" else s

    private fun yamlList(elements: List<String>): String =
        elements.joinToString(prefix = "[", postfix = "]", separator = ", ")
}
