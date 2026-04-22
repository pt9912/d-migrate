package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.*

internal class SqliteRoutineDdlHelper(private val quoteIdentifier: (String) -> String) {

    // -- Views -------------------------------------------------

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

        // Materialized views are not supported in SQLite; emit as regular VIEW with warning
        val notes = mutableListOf<TransformationNote>()
        if (view.materialized) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W103",
                objectName = name,
                message = "Materialized view '$name' is not supported in SQLite. Created as a regular VIEW instead.",
                hint = "If materialization is needed, consider caching query results in a regular table via triggers or application logic."
            )
        }

        val transformer = ViewQueryTransformer(DatabaseDialect.SQLITE)
        val (transformedQuery, queryNotes) = transformer.transform(query, view.sourceDialect)
        notes += queryNotes

        val sql = "CREATE VIEW IF NOT EXISTS ${quoteIdentifier(name)} AS\n$transformedQuery;"
        return DdlStatement(sql, notes)
    }

    // -- Functions ---------------------------------------------

    fun generateFunctions(
        functions: Map<String, FunctionDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        // SQLite does not support user-defined SQL functions via DDL. Skip all with E054.
        val statements = mutableListOf<DdlStatement>()
        for ((name, _) in functions) {
            skipped += SkippedObject("function", name, "Functions are not supported in SQLite DDL")
            statements += DdlStatement(
                "-- Function ${quoteIdentifier(name)} is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E054",
                        objectName = name,
                        message = "Function '$name' cannot be created via DDL in SQLite.",
                        hint = "Register custom functions programmatically via the SQLite C API or your application's SQLite driver."
                    )
                )
            )
        }
        return statements
    }

    // -- Procedures --------------------------------------------

    fun generateProcedures(
        procedures: Map<String, ProcedureDefinition>,
        skipped: MutableList<SkippedObject>
    ): List<DdlStatement> {
        // SQLite does not support stored procedures. Skip all with E054.
        val statements = mutableListOf<DdlStatement>()
        for ((name, _) in procedures) {
            skipped += SkippedObject("procedure", name, "Procedures are not supported in SQLite")
            statements += DdlStatement(
                "-- Procedure ${quoteIdentifier(name)} is not supported in SQLite",
                listOf(
                    TransformationNote(
                        type = NoteType.ACTION_REQUIRED,
                        code = "E054",
                        objectName = name,
                        message = "Procedure '$name' cannot be created in SQLite.",
                        hint = "Implement procedure logic at the application level."
                    )
                )
            )
        }
        return statements
    }

    // -- Triggers ----------------------------------------------

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

        if (trigger.sourceDialect != null && trigger.sourceDialect != "sqlite") {
            val action = ManualActionRequired(
                code = "E053", objectType = "trigger", objectName = name,
                reason = "Trigger '$name' was written for '${trigger.sourceDialect}' and must be manually rewritten for SQLite.",
                hint = "Rewrite the trigger body using SQLite-compatible syntax with BEGIN...END;.",
                sourceDialect = trigger.sourceDialect,
            )
            skipped += action.toSkipped()
            return DdlStatement("-- TODO: Rewrite trigger ${quoteIdentifier(name)} for SQLite (source dialect: ${trigger.sourceDialect})", listOf(action.toNote()))
        }

        val timing = trigger.timing.name
        val event = trigger.event.name
        val forEach = trigger.forEach.name

        val sql = buildString {
            append("CREATE TRIGGER ${quoteIdentifier(name)}\n")
            append("    $timing $event ON ${quoteIdentifier(trigger.table)}\n")
            append("    FOR EACH $forEach")
            if (trigger.condition != null) {
                append("\n    WHEN ${trigger.condition}")
            }
            append("\nBEGIN\n")
            append(body)
            append("\nEND;")
        }
        return DdlStatement(sql)
    }
}
