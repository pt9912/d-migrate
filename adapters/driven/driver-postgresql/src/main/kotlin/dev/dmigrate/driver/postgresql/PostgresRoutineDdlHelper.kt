package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class PostgresRoutineDdlHelper(private val quoteIdentifier: (String) -> String) {

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

        val transformer = ViewQueryTransformer(DatabaseDialect.POSTGRESQL)
        val (transformedQuery, queryNotes) = transformer.transform(query, view.sourceDialect)

        return if (view.materialized) {
            DdlStatement("CREATE MATERIALIZED VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", queryNotes)
        } else {
            DdlStatement("CREATE OR REPLACE VIEW ${quoteIdentifier(name)} AS\n$transformedQuery;", queryNotes)
        }
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

        if (fn.sourceDialect != null && fn.sourceDialect != "postgresql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "function", objectName = name,
                reason = "Function '$name' was written for '${fn.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                hint = "Rewrite the function body using PostgreSQL-compatible syntax.",
                sourceDialect = fn.sourceDialect,
            )
            skipped += action.toSkipped()
            val todo = "-- TODO: Rewrite function ${quoteIdentifier(name)} " +
                "for PostgreSQL (source dialect: ${fn.sourceDialect})"
            return DdlStatement(todo, listOf(action.toNote()))
        }

        val params = fn.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }
        val returns = fn.returns?.let {
            val type = it.type.uppercase()
            val params = if (it.precision != null) "(${it.precision}${if (it.scale != null) ",${it.scale}" else ""})" else ""
            " RETURNS $type$params"
        } ?: ""
        val language = fn.language ?: "plpgsql"

        val sql = buildString {
            append("CREATE OR REPLACE FUNCTION ${quoteIdentifier(name)}($params)$returns AS \$\$\n")
            append(body)
            append("\n\$\$ LANGUAGE $language;")
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

        if (proc.sourceDialect != null && proc.sourceDialect != "postgresql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "procedure", objectName = name,
                reason = "Procedure '$name' was written for '${proc.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                hint = "Rewrite the procedure body using PostgreSQL-compatible syntax.",
                sourceDialect = proc.sourceDialect,
            )
            skipped += action.toSkipped()
            val todo = "-- TODO: Rewrite procedure ${quoteIdentifier(name)} " +
                "for PostgreSQL (source dialect: ${proc.sourceDialect})"
            return DdlStatement(todo, listOf(action.toNote()))
        }

        val params = proc.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }
        val language = proc.language ?: "plpgsql"

        val sql = buildString {
            append("CREATE OR REPLACE PROCEDURE ${quoteIdentifier(name)}($params) AS \$\$\n")
            append(body)
            append("\n\$\$ LANGUAGE $language;")
        }
        return DdlStatement(sql)
    }

    // ── Triggers ─────────────────────────────────

    fun generateTriggers(
        triggers: Map<String, TriggerDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        return triggers.flatMap { (name, trigger) -> generateTrigger(name, trigger, skipped) }
    }

    private fun generateTrigger(
        name: String,
        trigger: TriggerDefinition,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        val body = trigger.body
        if (body == null) {
            val action = ManualActionRequired(
                code = "E053", objectType = "trigger", objectName = name,
                reason = "Trigger '$name' has no body and must be manually implemented.",
                hint = "Provide a trigger body in the schema definition.",
            )
            skipped += action.toSkipped()
            return listOf(DdlStatement("-- TODO: Implement trigger ${quoteIdentifier(name)}", listOf(action.toNote())))
        }

        if (trigger.sourceDialect != null && trigger.sourceDialect != "postgresql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "trigger", objectName = name,
                reason = "Trigger '$name' was written for '${trigger.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                hint = "Rewrite the trigger body using PostgreSQL-compatible syntax.",
                sourceDialect = trigger.sourceDialect,
            )
            skipped += action.toSkipped()
            val todo = "-- TODO: Rewrite trigger ${quoteIdentifier(name)} " +
                "for PostgreSQL (source dialect: ${trigger.sourceDialect})"
            return listOf(DdlStatement(todo, listOf(action.toNote())))
        }

        // PostgreSQL triggers require a separate trigger function
        val funcName = "trg_fn_${name}"
        val statements = mutableListOf<DdlStatement>()

        // 1. Create trigger function
        val funcSql = buildString {
            append("CREATE OR REPLACE FUNCTION ${quoteIdentifier(funcName)}() RETURNS TRIGGER AS \$\$\n")
            append(body)
            append("\n\$\$ LANGUAGE plpgsql;")
        }
        statements += DdlStatement(funcSql)

        // 2. Create trigger
        val timing = trigger.timing.name
        val event = trigger.event.name
        val forEach = trigger.forEach.name
        val triggerSql = buildString {
            append("CREATE TRIGGER ${quoteIdentifier(name)}\n")
            append("    $timing $event ON ${quoteIdentifier(trigger.table)}\n")
            append("    FOR EACH $forEach")
            if (trigger.condition != null) {
                append("\n    WHEN (${trigger.condition})")
            }
            append("\n    EXECUTE FUNCTION ${quoteIdentifier(funcName)}();")
        }
        statements += DdlStatement(triggerSql)

        return statements
    }
}
