package dev.dmigrate.driver

/**
 * Structured representation of a DDL generation decision that requires
 * manual intervention.
 *
 * Each instance maps to the existing DDL diagnostic contract:
 * - as [TransformationNote] with [NoteType.ACTION_REQUIRED]
 * - optionally as [SkippedObject] when no executable DDL was produced
 */
data class ManualActionRequired(
    val code: String,
    val objectType: String,
    val objectName: String,
    val reason: String,
    val hint: String? = null,
    val sourceDialect: String? = null,
) {
    /** Maps to an ACTION_REQUIRED [TransformationNote]. */
    fun toNote(phase: DdlPhase? = null): TransformationNote = TransformationNote(
        type = NoteType.ACTION_REQUIRED,
        code = code,
        objectName = objectName,
        message = reason,
        hint = hint,
        phase = phase,
    )

    /** Maps to a [SkippedObject] when no DDL was generated. */
    fun toSkipped(phase: DdlPhase? = null): SkippedObject = SkippedObject(
        type = objectType,
        name = objectName,
        reason = reason,
        code = code,
        hint = hint,
        phase = phase,
    )

    /** Legacy helper for callers that still need a plain TODO-style comment. */
    fun toTodoComment(): String = buildString {
        append("-- TODO: $reason")
        if (hint != null) append(" ($hint)")
    }
}
