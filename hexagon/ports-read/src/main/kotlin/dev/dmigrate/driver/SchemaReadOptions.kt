package dev.dmigrate.driver

/**
 * Options controlling which database object types to include when
 * reading a live schema via [SchemaReader].
 *
 * All flags default to `true`. CLI/IO concerns like output path,
 * format, or report mode do NOT belong here — they are application-layer
 * concerns.
 *
 * Dialect selection is also not part of these options because the dialect
 * is determined by the chosen [DatabaseDriver].
 */
data class SchemaReadOptions(
    val includeViews: Boolean = true,
    val includeProcedures: Boolean = true,
    val includeFunctions: Boolean = true,
    val includeTriggers: Boolean = true,
    /**
     * Declared preference resolving the inherent SQLite AUTOINCREMENT-width
     * ambiguity on reverse: SQLite's `INTEGER PRIMARY KEY AUTOINCREMENT` is a
     * 64-bit rowid and maps equally to the neutral 32-bit `identifier` contract
     * and to 64-bit `biginteger` + `generation: identity`. Only SQLite collapses
     * both (PG/MySQL distinguish by column width), so the tool cannot infer the
     * intent — the user declares it. Default is conservative (no regression);
     * the driver honours it, other readers ignore the field. See the
     * `reverse-preferences` slice / `reverse-preference-mechanism` spec.
     */
    val sqliteAutoincrement: SqliteAutoincrementReverse = SqliteAutoincrementReverse.IDENTIFIER,
)

/**
 * How the SQLite reverse renders an AUTOINCREMENT primary key into the neutral
 * model. The user surface is dialect-neutral *width* (`32` | `64`, CLI flag /
 * config `reverse.sqlite.autoincrement_width`); this enum is the internal
 * contract the surface maps onto.
 */
enum class SqliteAutoincrementReverse {
    /** Width 32 — the spec's `identifier` auto-increment contract. Default, conservative. */
    IDENTIFIER,

    /** Width 64 — `biginteger` + `generation: identity`, faithful to SQLite's 64-bit rowid. */
    BIGINTEGER_IDENTITY,
}
