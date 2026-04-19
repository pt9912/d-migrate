package dev.dmigrate.driver

/**
 * Structured representation of a DDL generation decision that requires
 * manual intervention. Replaces unstructured `-- TODO: ...` SQL comments
 * with a typed model.
 *
 * Each instance maps to the existing DDL diagnostic contract:
 * - as [TransformationNote] with [NoteType.ACTION_REQUIRED]
 * - optionally as [SkippedObject] when no executable DDL was produced
 *
 * In 0.9.1 the default rendering still emits `-- TODO: ...` comments
 * for backwards compatibility with existing tests and user expectations.
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
    fun toNote(): TransformationNote = TransformationNote(
        type = NoteType.ACTION_REQUIRED,
        code = code,
        objectName = objectName,
        message = reason,
        hint = hint,
    )

    /** Maps to a [SkippedObject] when no DDL was generated. */
    fun toSkipped(): SkippedObject = SkippedObject(
        type = objectType,
        name = objectName,
        reason = reason,
        code = code,
        hint = hint,
    )

    /** Renders as a `-- TODO: ...` SQL comment for backwards compatibility. */
    fun toTodoComment(): String = buildString {
        append("-- TODO: $reason")
        if (hint != null) append(" ($hint)")
    }
}
