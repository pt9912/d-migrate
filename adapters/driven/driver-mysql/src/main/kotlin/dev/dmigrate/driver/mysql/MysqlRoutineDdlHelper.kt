package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class MysqlRoutineDdlHelper(private val quoteIdentifier: (String) -> String) {

    // ── Views ────────────────────────────────────

    fun generateViews(
        views: Map<String, ViewDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return views.mapNotNull { (name, view) -> generateView(name, view, skipped) }
    }

    private fun generateView(
        name: String,
        view: ViewDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val query = view.query
        if (query == null) {
            skipped += SkippedObject("view", name, "No query defined")
            return null
        }

        val notes = mutableListOf<TransformationNote>()
        if (view.materialized) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W103",
                objectName = name,
                message = "Materialized views are not supported in MySQL. Created as a regular view instead.",
                hint = "Consider using a table with a scheduled refresh procedure to emulate materialized views."
            )
        }

        val transformer = ViewQueryTransformer(DatabaseDialect.MYSQL)
        val (transformedQuery, queryNotes) = transformer.transform(query, view.sourceDialect)
        notes += queryNotes

        return DdlStatement("CREATE OR REPLACE VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", notes)
    }

    // ── Functions ────────────────────────────────

    fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return functions.mapNotNull { (name, fn) -> generateFunction(name, fn, skipped) }
    }

    private fun generateFunction(
        name: String,
        fn: FunctionDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val body = fn.body
        if (body == null) {
            val action = ManualActionRequired(
                code = "E053", objectType = "function", objectName = name,
                reason = "Function '$name' has no body and must be manually implemented.",
                hint = "Provide a function body in the schema definition.",
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Implement function ${quoteIdentifier(name)}", listOf(action.toNote()))
        }

        if (fn.sourceDialect != null && fn.sourceDialect != "mysql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "function", objectName = name,
                reason = "Function '$name' was written for '${fn.sourceDialect}' and must be manually rewritten for MySQL.",
                hint = "Rewrite the function body using MySQL-compatible syntax.",
                sourceDialect = fn.sourceDialect,
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Rewrite function ${quoteIdentifier(name)} for MySQL (source dialect: ${fn.sourceDialect})", listOf(action.toNote()))
        }

        val params = fn.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }
        val returns = fn.returns?.let {
            val type = it.type.uppercase()
            val typeParams = if (it.precision != null) "(${it.precision}${if (it.scale != null) ",${it.scale}" else ""})" else ""
            "\nRETURNS $type$typeParams"
        } ?: ""
        val deterministic = when (fn.deterministic) {
            true -> "\nDETERMINISTIC"
            false -> "\nNOT DETERMINISTIC"
            null -> ""
        }

        val sql = buildString {
            append("DELIMITER //\n")
            append("CREATE FUNCTION ${quoteIdentifier(name)}($params)$returns$deterministic\n")
            append("BEGIN\n")
            append(body)
            append("\nEND //\n")
            append("DELIMITER ;")
        }
        return DdlStatement(sql)
    }

    // ── Procedures ───────────────────────────────

    fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return procedures.mapNotNull { (name, proc) -> generateProcedure(name, proc, skipped) }
    }

    private fun generateProcedure(
        name: String,
        proc: ProcedureDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val body = proc.body
        if (body == null) {
            val action = ManualActionRequired(
                code = "E053", objectType = "procedure", objectName = name,
                reason = "Procedure '$name' has no body and must be manually implemented.",
                hint = "Provide a procedure body in the schema definition.",
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Implement procedure ${quoteIdentifier(name)}", listOf(action.toNote()))
        }

        if (proc.sourceDialect != null && proc.sourceDialect != "mysql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "procedure", objectName = name,
                reason = "Procedure '$name' was written for '${proc.sourceDialect}' and must be manually rewritten for MySQL.",
                hint = "Rewrite the procedure body using MySQL-compatible syntax.",
                sourceDialect = proc.sourceDialect,
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Rewrite procedure ${quoteIdentifier(name)} for MySQL (source dialect: ${proc.sourceDialect})", listOf(action.toNote()))
        }

        val params = proc.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }

        val sql = buildString {
            append("DELIMITER //\n")
            append("CREATE PROCEDURE ${quoteIdentifier(name)}($params)\n")
            append("BEGIN\n")
            append(body)
            append("\nEND //\n")
            append("DELIMITER ;")
        }
        return DdlStatement(sql)
    }

    // ── Triggers ─────────────────────────────────

    fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        _tables: Map<String, TableDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return triggers.mapNotNull { (name, trigger) -> generateTrigger(name, trigger, skipped) }
    }

    private fun generateTrigger(
        name: String,
        trigger: TriggerDefinition,
        skipped: MutableList<SkippedObject>
    ): DdlStatement? {
        val body = trigger.body
        if (body == null) {
            val action = ManualActionRequired(
                code = "E053", objectType = "trigger", objectName = name,
                reason = "Trigger '$name' has no body and must be manually implemented.",
                hint = "Provide a trigger body in the schema definition.",
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Implement trigger ${quoteIdentifier(name)}", listOf(action.toNote()))
        }

        if (trigger.sourceDialect != null && trigger.sourceDialect != "mysql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "trigger", objectName = name,
                reason = "Trigger '$name' was written for '${trigger.sourceDialect}' and must be manually rewritten for MySQL.",
                hint = "Rewrite the trigger body using MySQL-compatible syntax.",
                sourceDialect = trigger.sourceDialect,
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Rewrite trigger ${quoteIdentifier(name)} for MySQL (source dialect: ${trigger.sourceDialect})", listOf(action.toNote()))
        }

        val timing = trigger.timing.name
        val event = trigger.event.name
        val forEach = trigger.forEach.name

        val sql = buildString {
            append("DELIMITER //\n")
            append("CREATE TRIGGER ${quoteIdentifier(name)}\n")
            append("    $timing $event ON ${quoteIdentifier(trigger.table)}\n")
            append("    FOR EACH $forEach\n")
            append("BEGIN\n")
            append(body)
            append("\nEND //\n")
            append("DELIMITER ;")
        }
        return DdlStatement(sql)
    }
}
