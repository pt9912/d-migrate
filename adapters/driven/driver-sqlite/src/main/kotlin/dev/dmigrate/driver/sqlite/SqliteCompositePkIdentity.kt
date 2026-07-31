package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.NeutralType

/**
 * W135 — an identity / AUTO_INCREMENT column that lands in a **composite** primary key.
 *
 * SQLite's AUTOINCREMENT is a single-column rowid alias, so such a column cannot back a
 * composite key: it degrades to a plain `INTEGER` member of the table-level `PRIMARY KEY`
 * clause and loses server-side auto-generation. Rendering the inline
 * `INTEGER PRIMARY KEY AUTOINCREMENT` **and** the composite clause would give the table two
 * primary keys, which SQLite rejects outright ("table has more than one primary key").
 *
 * The condition arises cross-dialect — e.g. a partitioned MySQL table whose partition key was
 * folded into the PK, reversed and re-generated for SQLite (Lastenheft 8.6 3-hop). This object
 * owns the single user-facing message so all three SQLite `CREATE TABLE` emitters agree:
 * the generate path ([SqliteColumnConstraintHelper]), the diff path ([SqliteDiffSimpleOps])
 * and the table-rebuild path ([SqliteRebuildRenderer]). SQLite mirror of the MySQL-side W118.
 */
internal object SqliteCompositePkIdentity {
    const val W_CODE = "W135"

    /**
     * True when [type] is an auto-incrementing identifier that is only part of a composite
     * primary key — i.e. its AUTOINCREMENT is dropped in the SQLite rendering (W135). The
     * diff/rebuild `columnLine` renders inline AUTOINCREMENT only for [NeutralType.Identifier]
     * (not for `ColumnGeneration.Identity`), so this predicate matches that emitter.
     */
    fun isDroppedAutoincrement(type: NeutralType, isSolePrimaryKey: Boolean): Boolean =
        !isSolePrimaryKey && type is NeutralType.Identifier && type.autoIncrement

    fun message(colName: String): String =
        "AUTOINCREMENT dropped for '$colName': SQLite AUTOINCREMENT requires a single-column " +
            "INTEGER PRIMARY KEY, but '$colName' is part of a composite primary key."

    const val HINT: String =
        "The column is rendered as a plain INTEGER member of the composite key. Supply identifier " +
            "values explicitly, or redesign the key if server-side auto-generation is required."
}
