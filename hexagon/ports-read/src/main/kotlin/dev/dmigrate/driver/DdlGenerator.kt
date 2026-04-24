package dev.dmigrate.driver

import dev.dmigrate.core.model.SchemaDefinition

interface DdlGenerator {
    val dialect: DatabaseDialect
    fun generate(schema: SchemaDefinition, options: DdlGenerationOptions = DdlGenerationOptions()): DdlResult
    fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions = DdlGenerationOptions()): DdlResult
}

/** DDL output phase for import-friendly schema artifacts (0.9.2). */
enum class DdlPhase {
    /** Structural DDL: tables, columns, sequences, indexes, constraints. */
    PRE_DATA,
    /** Deferred DDL: triggers, functions, procedures, views with routine deps. */
    POST_DATA,
}

data class DdlResult(
    val statements: List<DdlStatement>,
    val skippedObjects: List<SkippedObject> = emptyList(),
    val globalNotes: List<TransformationNote> = emptyList(),
) {
    /** All notes: statement-bound notes in statement order, then globalNotes. */
    val notes: List<TransformationNote>
        get() = statements.flatMap { it.notes } + globalNotes

    /** Render all statements as a single SQL string (unchanged from pre-0.9.2). */
    fun render(): String = statements.joinToString("\n\n") { it.render() }

    /** Statements belonging to the given phase, preserving original order. */
    fun statementsForPhase(phase: DdlPhase): List<DdlStatement> =
        statements.filter { it.phase == phase }

    /** Render only statements of the given phase; empty phase yields "". */
    fun renderPhase(phase: DdlPhase): String =
        statementsForPhase(phase).joinToString("\n\n") { it.render() }

    /**
     * Notes for a phase: statement-bound notes (via statement phase) in
     * statement order, then explicitly phase-tagged globalNotes. A note
     * attached to a statement always inherits the statement's phase;
     * its own [TransformationNote.phase] is ignored for filtering.
     */
    fun notesForPhase(phase: DdlPhase): List<TransformationNote> =
        statements.filter { it.phase == phase }.flatMap { it.notes } +
            globalNotes.filter { it.phase == phase }

    /** Skipped objects explicitly tagged with the given phase. */
    fun skippedObjectsForPhase(phase: DdlPhase): List<SkippedObject> =
        skippedObjects.filter { it.phase == phase }
}

data class DdlStatement(
    val sql: String,
    val notes: List<TransformationNote> = emptyList(),
    val phase: DdlPhase = DdlPhase.PRE_DATA,
) {
    fun render(): String = buildString {
        for (note in notes) {
            appendLine("-- [${note.code}] ${note.message}")
            if (note.hint != null) appendLine("-- Hint: ${note.hint}")
        }
        if (sql.isBlank()) {
            if (isNotEmpty() && last() == '\n') {
                setLength(length - 1)
            }
        } else {
            append(sql)
        }
    }
}

data class TransformationNote(
    val type: NoteType,
    val code: String,
    val objectName: String,
    val message: String,
    val hint: String? = null,
    val blocksTable: Boolean = false,
    val phase: DdlPhase? = null,
)

enum class NoteType { INFO, WARNING, ACTION_REQUIRED }

data class SkippedObject(
    val type: String,
    val name: String,
    val reason: String,
    val code: String? = null,
    val hint: String? = null,
    val phase: DdlPhase? = null,
)
