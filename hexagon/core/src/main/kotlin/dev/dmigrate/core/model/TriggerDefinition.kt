package dev.dmigrate.core.model

data class TriggerDefinition(
    val description: String? = null,
    val table: String,
    val events: Set<TriggerEvent>,
    val timing: TriggerTiming,
    val forEach: TriggerForEach = TriggerForEach.ROW,
    val condition: String? = null,
    val body: String? = null,
    val dependencies: DependencyInfo? = null,
    val sourceDialect: String? = null,
) {
    /**
     * F4 (docs/planning/in-progress/sample-db-roundtrip-findings.md):
     * convenience for the dominant single-event case — a trigger that
     * fires on exactly one DML event. Exact and non-lossy: it wraps the
     * single event in a one-element set, so the multi-event-aware model
     * stays the single source of truth while single-event call sites
     * read naturally.
     */
    constructor(
        description: String? = null,
        table: String,
        event: TriggerEvent,
        timing: TriggerTiming,
        forEach: TriggerForEach = TriggerForEach.ROW,
        condition: String? = null,
        body: String? = null,
        dependencies: DependencyInfo? = null,
        sourceDialect: String? = null,
    ) : this(
        description = description,
        table = table,
        events = setOf(event),
        timing = timing,
        forEach = forEach,
        condition = condition,
        body = body,
        dependencies = dependencies,
        sourceDialect = sourceDialect,
    )
}

enum class TriggerEvent { INSERT, UPDATE, DELETE }
enum class TriggerTiming { BEFORE, AFTER, INSTEAD_OF }
enum class TriggerForEach { ROW, STATEMENT }

/**
 * Trigger events in canonical declaration order (INSERT, UPDATE, DELETE).
 *
 * A `Set<TriggerEvent>` is unordered, but reverse readers may observe the
 * events of a multi-event trigger in any order (PostgreSQL surfaces one
 * `information_schema.triggers` row per event). Pinning emission and
 * fingerprinting to the enum declaration order keeps the generated DDL and
 * the migration fingerprint deterministic regardless of observation order.
 */
fun Collection<TriggerEvent>.canonicalOrder(): List<TriggerEvent> =
    TriggerEvent.entries.filter { it in this }

/**
 * Renders the event set as a SQL trigger event clause in canonical order:
 * a single event yields e.g. `INSERT`; multiple events yield
 * `INSERT OR UPDATE` — PostgreSQL's multi-event trigger syntax.
 *
 * MySQL and SQLite have no multi-event trigger grammar, so their reverse
 * readers only ever produce single-event sets and their emit helpers reach
 * the ` OR ` join only for the (unreachable) cross-dialect case, which the
 * generators already gate with an `E053` manual-action skip.
 */
fun Collection<TriggerEvent>.toSqlEventClause(): String =
    canonicalOrder().joinToString(" OR ") { it.name }
