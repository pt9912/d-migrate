package dev.dmigrate.profiling.types

/**
 * Codes for the initial migration-relevant warning rule catalog.
 *
 * Each code maps to exactly one rule in the [WarningEvaluator].
 * The catalog is deliberately small for 0.7.5 and focuses on findings
 * that matter before a database migration.
 */
enum class WarningCode {
    HIGH_NULL_RATIO,
    CONTAINS_EMPTY_STRINGS,
    CONTAINS_BLANK_STRINGS,
    HIGH_CARDINALITY,
    LOW_CARDINALITY,
    DUPLICATE_VALUES,
    INVALID_TARGET_TYPE_VALUES,
    POSSIBLE_PLACEHOLDER_VALUES,
}
