package dev.dmigrate.driver.mysql

/**
 * Internal data types for MySQL sequence reverse-engineering support (0.9.4 AP 6.1).
 *
 * These types model the support-scan snapshot that the reader produces
 * before D2/D3 materialize actual SequenceDefinitions. They are internal
 * to the MySQL driver and not part of the public ports API.
 */

/** Canonical namespace of a single reader run. */
data class ReverseScope(
    val catalogName: String?,
    val schemaName: String,
)


/** Status of the dmg_sequences support table. */
enum class SupportTableState {
    /** Table does not exist (existence check succeeded negatively). */
    MISSING,
    /** Table present and readable. */
    AVAILABLE,
    /** Table present but column shape is not canonical. */
    INVALID_SHAPE,
    /** Existence undecidable or access denied. */
    NOT_ACCESSIBLE,
}

/** Status of an individual support routine (dmg_nextval / dmg_setval). */
enum class SupportRoutineState {
    /** Routine present, marker and signature verified. */
    CONFIRMED,
    /** Routine not found. */
    MISSING,
    /** Marker present but signature does not match (wrong return type, param count, or specific marker). */
    NON_CANONICAL,
    /** Permission or lookup error. */
    NOT_ACCESSIBLE,
}

/** Status of a potential support trigger. */
enum class SupportTriggerState {
    /** Marker present and body canonical. */
    CONFIRMED,
    /** Name matches pattern but marker comment is absent. */
    MISSING_MARKER,
    /** Marker present but body not canonically parseable. */
    NON_CANONICAL,
    /** Permission or lookup error. */
    NOT_ACCESSIBLE,
    /** Name collision with a user-defined trigger. */
    USER_OBJECT,
}

/** Pre-validated row from dmg_sequences. */
data class SequenceRowSnapshot(
    val name: String,
    val nextValue: Long,
    val incrementBy: Long,
    val minValue: Long?,
    val maxValue: Long?,
    val cycleEnabled: Boolean,
    val cacheSize: Int?,
    val managedBy: String,
    val formatVersion: String,
    /** Whether managed_by/format_version markers pass validation. */
    val valid: Boolean,
    /** Non-null if this row's key is ambiguous or conflicting. */
    val conflictReason: String?,
)

/** Diagnostic key: either sequence-level or column-level. */
sealed interface DiagnosticKey

data class SequenceDiagnosticKey(
    val scope: ReverseScope,
    val sequenceName: String,
) : DiagnosticKey

data class ColumnDiagnosticKey(
    val scope: ReverseScope,
    val tableName: String,
    val columnName: String,
) : DiagnosticKey

/** Aggregatable diagnostic basis (precursor to SchemaReadNote). */
data class SupportDiagnostic(
    val key: DiagnosticKey,
    val causes: List<String>,
    val emitsW116: Boolean,
)

/** Complete internal support snapshot produced by the sequence support scan. */
data class MysqlSequenceSupportSnapshot(
    val scope: ReverseScope,
    val supportTableState: SupportTableState,
    val sequenceRows: List<SequenceRowSnapshot>,
    val ambiguousKeys: Set<String>,
    val routineStates: Map<String, SupportRoutineState>,
    val triggerStates: Map<String, SupportTriggerState>,
    val diagnostics: List<SupportDiagnostic>,
) {
    companion object {
        /** Snapshot for the non-sequence case (no dmg_sequences table found). */
        fun nonSequence(scope: ReverseScope) = MysqlSequenceSupportSnapshot(
            scope = scope,
            supportTableState = SupportTableState.MISSING,
            sequenceRows = emptyList(),
            ambiguousKeys = emptySet(),
            routineStates = emptyMap(),
            triggerStates = emptyMap(),
            diagnostics = emptyList(),
        )
    }
}
