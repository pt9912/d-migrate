package dev.dmigrate.core.diff.migration

/**
 * Standard tie-breaker phases for [DiffOperation]s that are dependency-
 * independent. The [DiffPlanner] (Phase C) may use these to deterministically
 * order operations after the topological sort by explicit
 * [DiffOperation.dependencies] has run.
 *
 * Phase ordering is **not** authoritative on its own: a Drop-direction
 * dependency from `DropTable("orders")` to `DropConstraint("fk_orders_user")`
 * forces the constraint drop to come first regardless of phase. Phases only
 * resolve ties between operations with no explicit dependency edge.
 *
 * The set is derived from `docs/planning/in-progress/diffresult-migration-plan.md
 * §4.4`. A concrete dialect generator MAY collapse adjacent phases (e.g.
 * SQLite-Rebuild treats columns + constraints as one rebuild unit) as long
 * as the resulting SQL preserves the planner's dependency contract.
 */
enum class DiffPhase {
    /** Temporary helpers, SQLite-Rebuild preparation. */
    PREPARE,

    /** Custom Types / Domains / Enums. */
    TYPES,

    /** Tables created or dropped. */
    TABLES,

    /** Column add / alter / drop. */
    COLUMNS,

    /** PK / FK / UNIQUE; CHECK / EXCLUDE only when the comparator surfaces them. */
    CONSTRAINTS,

    /** Indices. */
    INDEXES,

    /** Sequences and sequence metadata. */
    SEQUENCES,

    /** Functions / Procedures. */
    ROUTINES,

    /** Views and materialized views. */
    VIEWS,

    /** Triggers. */
    TRIGGERS,

    /** Temporary objects, rebuild cleanup. */
    CLEANUP,
}
