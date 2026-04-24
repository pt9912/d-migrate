package dev.dmigrate.driver

/**
 * Declares which schema object types a target dialect can natively
 * generate, rewrite, or must skip/flag as manual action.
 *
 * Resolved per [DatabaseDialect] via [DialectCapabilities.forDialect].
 * Generators consume this to make consistent generate/skip/action-required
 * decisions without scattered `when (dialect)` checks.
 */
data class DialectCapabilities(
    val supportsViews: Boolean,
    val supportsFunctions: Boolean,
    val supportsProcedures: Boolean,
    val supportsTriggers: Boolean,
    val supportsSequences: Boolean,
    val supportsCustomTypes: Boolean,
    val supportsPartitioning: Boolean,
    /** Whether cross-dialect routine bodies can be rewritten (placeholder for future rewrite engine). */
    val supportsRoutineRewrite: Boolean = false,
    /** Whether the dialect supports disabling FK checks during import (MySQL/SQLite: yes, PostgreSQL: no). */
    val supportsDisableFkChecks: Boolean = false,
    /** Whether the dialect supports `triggerMode=disable` (PostgreSQL: yes, others: no). */
    val supportsTriggerDisable: Boolean = false,
    /** Whether the dialect supports `triggerMode=strict` (PostgreSQL: yes, others: no). */
    val supportsTriggerStrict: Boolean = false,
    /** Whether the dialect supports a `--schema` parameter for namespace scoping. */
    val supportsSchemaParameter: Boolean = false,
) {
    companion object {
        fun forDialect(dialect: DatabaseDialect): DialectCapabilities = when (dialect) {
            DatabaseDialect.POSTGRESQL -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = true,
                supportsProcedures = true,
                supportsTriggers = true,
                supportsSequences = true,
                supportsCustomTypes = true,
                supportsPartitioning = true,
                supportsDisableFkChecks = false,
                supportsTriggerDisable = true,
                supportsTriggerStrict = true,
                supportsSchemaParameter = true,
            )
            DatabaseDialect.MYSQL -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = true,
                supportsProcedures = true,
                supportsTriggers = true,
                supportsSequences = false,
                supportsCustomTypes = false,
                supportsPartitioning = true,
                supportsDisableFkChecks = true,
                supportsTriggerDisable = false,
                supportsTriggerStrict = false,
                supportsSchemaParameter = true,
            )
            DatabaseDialect.SQLITE -> DialectCapabilities(
                supportsViews = true,
                supportsFunctions = false,
                supportsProcedures = false,
                supportsTriggers = true,
                supportsSequences = false,
                supportsCustomTypes = false,
                supportsPartitioning = false,
                supportsDisableFkChecks = true,
                supportsTriggerDisable = false,
                supportsTriggerStrict = false,
                supportsSchemaParameter = false,
            )
        }
    }
}
