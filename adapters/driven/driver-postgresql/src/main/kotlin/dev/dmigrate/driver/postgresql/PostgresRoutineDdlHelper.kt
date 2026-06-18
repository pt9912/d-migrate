package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class PostgresRoutineDdlHelper(private val quoteIdentifier: (String) -> String) {
    private fun actionRequired(action: ManualActionRequired): DdlStatement =
        DdlStatement(sql = "", notes = listOf(action.toNote()))

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
        val portability = transformer.assessPortability(query, view.sourceDialect)
        if (!portability.portable) {
            val action = ManualActionRequired(
                code = "E053", objectType = "view", objectName = name,
                reason = "View '$name' body is not portable to PostgreSQL (${portability.reason}); " +
                    "d-migrate does not translate view bodies between dialects.",
                hint = "Rewrite the view body with PostgreSQL-compatible syntax and re-run.",
                sourceDialect = view.sourceDialect,
            )
            skipped += action.toSkipped()
            return actionRequired(action)
        }

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
        return functions.mapNotNull { (key, fn) -> generateFunction(ObjectKeyCodec.routineName(key), fn, skipped) }
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
            return actionRequired(action)
        }

        if (fn.sourceDialect != null && fn.sourceDialect != "postgresql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "function", objectName = name,
                reason = "Function '$name' was written for '${fn.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                hint = "Rewrite the function body using PostgreSQL-compatible syntax.",
                sourceDialect = fn.sourceDialect,
            )
            skipped += action.toSkipped()
            return actionRequired(action)
        }

        val params = fn.parameters.joinToString(", ") { param ->
            val direction = if (param.direction != ParameterDirection.IN) "${param.direction.name} " else ""
            "$direction${quoteIdentifier(param.name)} ${param.type.uppercase()}"
        }
        val returns = fn.returns?.let {
            val type = it.type.uppercase()
            val precision = if (it.precision != null) "(${it.precision}${if (it.scale != null) ",${it.scale}" else ""})" else ""
            // K2: a plpgsql body using `RETURN NEXT` / `RETURN QUERY` is
            // set-returning; emit SETOF so the CREATE matches the body (the
            // reverse return-type projection does not carry the set flag).
            val setof = if (!type.contains("SETOF") && isSetReturningBody(body)) "SETOF " else ""
            " RETURNS $setof$type$precision"
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
        return procedures.mapNotNull { (key, proc) -> generateProcedure(ObjectKeyCodec.routineName(key), proc, skipped) }
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
            return actionRequired(action)
        }

        if (proc.sourceDialect != null && proc.sourceDialect != "postgresql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "procedure", objectName = name,
                reason = "Procedure '$name' was written for '${proc.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                hint = "Rewrite the procedure body using PostgreSQL-compatible syntax.",
                sourceDialect = proc.sourceDialect,
            )
            skipped += action.toSkipped()
            return actionRequired(action)
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
            return listOf(actionRequired(action))
        }

        if (trigger.sourceDialect != null && trigger.sourceDialect != "postgresql") {
            val action = ManualActionRequired(
                code = "E053", objectType = "trigger", objectName = name,
                reason = "Trigger '$name' was written for '${trigger.sourceDialect}' and must be manually rewritten for PostgreSQL.",
                hint = "Rewrite the trigger body using PostgreSQL-compatible syntax.",
                sourceDialect = trigger.sourceDialect,
            )
            skipped += action.toSkipped()
            return listOf(actionRequired(action))
        }

        val statements = mutableListOf<DdlStatement>()

        // N6: the PG reverse stores `information_schema.triggers.action_statement`
        // (`EXECUTE FUNCTION fn()`) as the body — that is the trigger's action
        // clause, not a plpgsql body. Reference the existing function directly
        // then; only a real plpgsql body gets wrapped in a CREATE FUNCTION.
        val actionClause = if (isExecuteActionStatement(body)) {
            body.trim().trimEnd(';')
        } else {
            val funcName = "trg_fn_${name}"
            statements += DdlStatement(
                buildString {
                    append("CREATE OR REPLACE FUNCTION ${quoteIdentifier(funcName)}() RETURNS TRIGGER AS \$\$\n")
                    append(body)
                    append("\n\$\$ LANGUAGE plpgsql;")
                }
            )
            "EXECUTE FUNCTION ${quoteIdentifier(funcName)}()"
        }

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
            append("\n    $actionClause;")
        }
        statements += DdlStatement(triggerSql)

        return statements
    }

    /** N6: true when the body is already a PG trigger action (`EXECUTE FUNCTION/PROCEDURE …`). */
    private fun isExecuteActionStatement(body: String): Boolean =
        body.trimStart().matches(Regex("(?is)^EXECUTE\\s+(FUNCTION|PROCEDURE)\\s+.+"))

    /** K2: true when a plpgsql body returns a set (`RETURN NEXT` / `RETURN QUERY`). */
    private fun isSetReturningBody(body: String): Boolean =
        Regex("(?i)\\bRETURN\\s+(NEXT|QUERY)\\b").containsMatchIn(body)
}
