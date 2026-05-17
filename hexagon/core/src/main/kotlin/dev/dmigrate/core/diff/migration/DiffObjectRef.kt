package dev.dmigrate.core.diff.migration

/**
 * Object types a [DiffOperation] can target. The discriminator is part
 * of the [DiffObjectRef.path] interpretation: `TABLE` → `[tableName]`,
 * `COLUMN` → `[tableName, columnName]`, schema-wide objects → `[name]`.
 */
enum class DiffObjectType {
    TABLE,
    COLUMN,
    PRIMARY_KEY,
    CONSTRAINT,
    INDEX,
    CUSTOM_TYPE,
    SEQUENCE,
    VIEW,
    MATERIALIZED_VIEW,
    FUNCTION,
    PROCEDURE,
    TRIGGER,
}

/**
 * Qualified, structurally stable reference to the schema object a
 * [DiffOperation] targets. The [path] is the canonical identity used
 * for equality, dependency edges, and Operation-ID derivation.
 *
 * Path conventions per `docs/planning/done/diffresult-migration-plan.md
 * §4.3`:
 *
 * - `TABLE`: `["orders"]`
 * - `COLUMN`: `["orders", "status"]`
 * - `PRIMARY_KEY`: `["orders"]` (the table owns the PK)
 * - `CONSTRAINT`: `["orders", "fk_orders_customer"]`
 * - `INDEX`: `["orders", "idx_orders_created_at"]`
 * - schema-wide (`CUSTOM_TYPE`, `SEQUENCE`, `VIEW`, `FUNCTION`,
 *   `PROCEDURE`, `TRIGGER`): `["status_enum"]` etc.
 *
 * The constructor enforces the path-arity rule per [type] so that
 * downstream consumers can rely on `path[0]` / `path.getOrNull(1)`
 * without re-validating.
 */
data class DiffObjectRef(
    val type: DiffObjectType,
    val path: List<String>,
) {
    init {
        require(path.isNotEmpty()) { "DiffObjectRef.path must not be empty" }
        require(path.all { it.isNotBlank() }) { "DiffObjectRef.path entries must not be blank" }
        val expectedArity = when (type) {
            DiffObjectType.TABLE,
            DiffObjectType.PRIMARY_KEY,
            DiffObjectType.CUSTOM_TYPE,
            DiffObjectType.SEQUENCE,
            DiffObjectType.VIEW,
            DiffObjectType.MATERIALIZED_VIEW,
            DiffObjectType.FUNCTION,
            DiffObjectType.PROCEDURE,
            DiffObjectType.TRIGGER -> 1
            DiffObjectType.COLUMN,
            DiffObjectType.CONSTRAINT,
            DiffObjectType.INDEX -> 2
        }
        require(path.size == expectedArity) {
            "DiffObjectRef.path size for $type must be $expectedArity, got ${path.size}"
        }
    }

    /** Joined display form, e.g. `"orders.status"`. */
    val displayName: String
        get() = path.joinToString(".")

    /** Top-level (schema-wide) name: `path[0]`. */
    val rootName: String
        get() = path[0]
}
