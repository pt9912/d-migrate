package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.DefaultValue

/**
 * Materialises the neutral model's "PK ⇒ NOT NULL" invariant for SQLite.
 *
 * SQLite is the only supported dialect where `PRIMARY KEY` does **not**
 * imply `NOT NULL`: a plain `PRIMARY KEY` column still accepts NULL (only
 * `INTEGER PRIMARY KEY` and `WITHOUT ROWID` tables enforce it). Every other
 * dialect enforces it through the PK clause, so the neutral model leaves
 * `required` unset on PK columns and the reverse omits it — the effective
 * constraint is `required OR part of the primary key` (see
 * `ImportTableValidator`). Without re-materialising `NOT NULL` here, a schema
 * that d-migrate reversed and then re-generated itself would silently drop
 * the `NOT NULL` constraint from every PK column.
 *
 * Two cases are deliberately left untouched:
 * - **Sequence-backed columns** (`DefaultValue.SequenceNextVal`): their SQLite
 *   emulation populates the value with an `AFTER INSERT` trigger and needs a
 *   transient NULL, so [SqliteSequenceDdlSupport.shouldSuppressNotNull] drops
 *   `NOT NULL` for them on purpose.
 * - **Rowid-alias columns** (`INTEGER PRIMARY KEY [AUTOINCREMENT]`) never
 *   render a `required`-driven `NOT NULL` — they take a dedicated code path in
 *   [SqliteColumnConstraintHelper] — so this promotion is a no-op for them.
 */
internal object SqlitePrimaryKeyNullability {

    /**
     * Returns [col] with `required = true` when it is a non-required primary-key
     * column that is not sequence-backed; otherwise returns [col] unchanged.
     */
    fun materialize(colName: String, col: ColumnDefinition, primaryKey: List<String>): ColumnDefinition =
        if (!col.required && colName in primaryKey && col.default !is DefaultValue.SequenceNextVal) {
            col.copy(required = true)
        } else {
            col
        }
}
