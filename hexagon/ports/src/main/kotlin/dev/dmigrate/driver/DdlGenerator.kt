package dev.dmigrate.driver

import dev.dmigrate.core.model.SchemaDefinition

interface DdlGenerator {
    val dialect: DatabaseDialect
    fun generate(schema: SchemaDefinition, options: DdlGenerationOptions = DdlGenerationOptions()): DdlResult
    fun generateRollback(schema: SchemaDefinition, options: DdlGenerationOptions = DdlGenerationOptions()): DdlResult
}

data class DdlResult(
    val statements: List<DdlStatement>,
    val skippedObjects: List<SkippedObject> = emptyList()
) {
    val notes: List<TransformationNote> get() = statements.flatMap { it.notes }

    fun render(): String = statements.joinToString("\n\n") { it.render() }
}

data class DdlStatement(
    val sql: String,
    val notes: List<TransformationNote> = emptyList()
) {
    fun render(): String = buildString {
        for (note in notes) {
            appendLine("-- [${note.code}] ${note.message}")
            if (note.hint != null) appendLine("-- Hint: ${note.hint}")
        }
        append(sql)
    }
}

data class TransformationNote(
    val type: NoteType,
    val code: String,
    val objectName: String,
    val message: String,
    val hint: String? = null
)

enum class NoteType { INFO, WARNING, ACTION_REQUIRED }

data class SkippedObject(
    val type: String,
    val name: String,
    val reason: String,
    val code: String? = null,
    val hint: String? = null,
)
