package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.metadata.*

/**
 * Shared JDBC metadata queries for SQLite.
 *
 * Operates on an already-borrowed connection via [JdbcMetadataSession].
 * Used by both [SqliteTableLister] and [SqliteSchemaReader].
 */
object SqliteMetadataQueries {

    fun listTableRefs(session: JdbcMetadataSession): List<TableRef> {
        val rows = session.queryList(
            "SELECT name, type FROM sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY name"
        )
        return rows.map { row ->
            TableRef(name = row["name"] as String)
        }
    }

    /** Lists all table names including virtual tables. */
    fun listAllTableEntries(session: JdbcMetadataSession): List<Pair<String, String>> {
        val rows = session.queryList(
            "SELECT name, sql FROM sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY name"
        )
        return rows.map { (it["name"] as String) to (it["sql"] as? String ?: "") }
    }

    fun listColumns(session: JdbcMetadataSession, table: String): List<ColumnProjection> {
        val rows = session.queryList("PRAGMA table_info(${SqlIdentifiers.quoteStringLiteral(table)})")
        return rows.map { row ->
            val rawType = (row["type"] as? String) ?: ""
            ColumnProjection(
                name = row["name"] as String,
                dataType = rawType,
                isNullable = (row["notnull"] as Number).toInt() == 0,
                columnDefault = row["dflt_value"]?.toString(),
                ordinalPosition = (row["cid"] as Number).toInt(),
                isAutoIncrement = false, // determined separately via CREATE TABLE SQL
            )
        }
    }

    fun listPrimaryKeyColumns(session: JdbcMetadataSession, table: String): List<String> {
        val rows = session.queryList("PRAGMA table_info(${SqlIdentifiers.quoteStringLiteral(table)})")
        return rows.filter { (it["pk"] as Number).toInt() > 0 }
            .sortedBy { (it["pk"] as Number).toInt() }
            .map { it["name"] as String }
    }

    fun listForeignKeys(session: JdbcMetadataSession, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList("PRAGMA foreign_key_list(${SqlIdentifiers.quoteStringLiteral(table)})")
        return rows.groupBy { it["id"] as Number }.map { (_, fkRows) ->
            val sorted = fkRows.sortedBy { (it["seq"] as Number).toInt() }
            val first = sorted.first()
            ForeignKeyProjection(
                name = "fk_${first["id"]}",
                columns = sorted.map { it["from"] as String },
                referencedTable = first["table"] as String,
                referencedColumns = sorted.map { it["to"] as String },
                onDelete = (first["on_delete"] as? String)?.takeIf { it != "NO ACTION" },
                onUpdate = (first["on_update"] as? String)?.takeIf { it != "NO ACTION" },
            )
        }
    }

    fun listIndices(session: JdbcMetadataSession, table: String): List<IndexProjection> {
        val indexRows = session.queryList("PRAGMA index_list(${SqlIdentifiers.quoteStringLiteral(table)})")
        return indexRows.mapNotNull { idx ->
            val indexName = idx["name"] as String
            // Skip SQLite autoindex (backing indices for PK/UNIQUE constraints)
            if (indexName.startsWith("sqlite_autoindex_")) return@mapNotNull null
            val colRows = session.queryList("PRAGMA index_xinfo(${SqlIdentifiers.quoteStringLiteral(indexName)})")
                .filter { ((it["key"] as? Number)?.toInt() ?: 1) == 1 }
                .sortedBy { (it["seqno"] as Number).toInt() }
            val cols = colRows.mapNotNull { it["name"] as? String }
            if (cols.isEmpty()) return@mapNotNull null
            val createSql = session.querySingle(
                "SELECT sql FROM sqlite_master WHERE type = 'index' AND name = ?",
                indexName,
            )?.get("sql") as? String
            IndexProjection(
                name = indexName,
                columns = cols,
                isUnique = (idx["unique"] as Number).toInt() == 1,
                directions = colRows.map { row ->
                    if (((row["desc"] as? Number)?.toInt() ?: 0) == 1) IndexSortDirection.DESC else null
                },
                where = extractIndexWhere(createSql),
            )
        }
    }

    private fun extractIndexWhere(sql: String?): String? {
        if (sql == null) return null
        val match = Regex("""\sWHERE\s""", RegexOption.IGNORE_CASE).find(sql) ?: return null
        return sql.substring(match.range.last + 1).trim().removeSuffix(";").trim().takeIf { it.isNotEmpty() }
    }

    fun listViews(session: JdbcMetadataSession): List<Pair<String, String?>> {
        val rows = session.queryList(
            "SELECT name, sql FROM sqlite_master WHERE type = 'view' ORDER BY name"
        )
        return rows.map { (it["name"] as String) to (it["sql"] as? String) }
    }

    fun listTriggers(session: JdbcMetadataSession): List<Map<String, Any?>> {
        return session.queryList(
            "SELECT name, tbl_name, sql FROM sqlite_master WHERE type = 'trigger' ORDER BY name"
        )
    }

    /**
     * 0.9.7 SQLite-Sequence Phase D: trigger list including the
     * sqlite_master ROWID as the implicit creation-order key. Used
     * by `SqliteSequenceReverseSupport` to detect the W124
     * "user-defined BEFORE INSERT trigger created before the
     * sequence-support trigger" masking risk (Plan §5.1 line
     * 1397–1402).
     */
    fun listTriggersWithRowid(session: JdbcMetadataSession): List<Map<String, Any?>> {
        return session.queryList(
            "SELECT rowid, name, tbl_name, sql FROM sqlite_master " +
                "WHERE type = 'trigger' ORDER BY rowid"
        )
    }

    /** Get the CREATE TABLE SQL for a table. */
    fun getCreateSql(session: JdbcMetadataSession, table: String): String? {
        val row = session.querySingle(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", table
        )
        return row?.get("sql") as? String
    }

    /**
     * 0.9.7 SQLite-Sequence Phase D: existence probe for the
     * `dmg_sequences` helper table. Returns `true` if the table is
     * present, `false` if absent, `null` if the query fails (e.g.
     * lack of read privileges on `sqlite_master` — practically
     * impossible for SQLite, but kept symmetric with the MySQL
     * snapshot's `NOT_ACCESSIBLE` state).
     */
    fun checkDmgSequencesTableExists(session: JdbcMetadataSession): Boolean? = try {
        val row = session.querySingle(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'dmg_sequences'",
        )
        row != null
    } catch (_: Exception) {
        null
    }

    /**
     * 0.9.7 SQLite-Sequence Phase D: shape check for `dmg_sequences`.
     * The reverse path only accepts the table if it carries the
     * canonical column set from Plan §3.2 — `managed_by`,
     * `format_version`, `name`, `next_value`, `last_returned_value`,
     * `exhausted`, `increment_by`, `min_value`, `max_value`,
     * `cycle_enabled`, `cache_size`. Extra columns are tolerated
     * (a future emulation-format bump may add fields); missing
     * canonical columns trigger `INVALID_SHAPE`.
     */
    fun checkDmgSequencesShape(session: JdbcMetadataSession): Boolean {
        val cols = listColumns(session, "dmg_sequences").map { it.name }.toSet()
        return REQUIRED_DMG_SEQUENCES_COLUMNS.all { it in cols }
    }

    /**
     * 0.9.7 SQLite-Sequence Phase D: scan the helper-table rows.
     * Filters by `managed_by IN (...)` and `format_version IN (...)`
     * so operator-inserted rows (test fixtures, manual bookkeeping)
     * are not mistaken for canonical d-migrate sequences.
     */
    fun listDmgSequencesRows(session: JdbcMetadataSession): List<Map<String, Any?>> =
        session.queryList(
            "SELECT \"managed_by\", \"format_version\", \"name\", \"next_value\", " +
                "\"last_returned_value\", \"exhausted\", \"increment_by\", " +
                "\"min_value\", \"max_value\", \"cycle_enabled\", \"cache_size\" " +
                "FROM \"dmg_sequences\" " +
                "WHERE \"managed_by\" = 'd-migrate' " +
                "AND \"format_version\" = 'sqlite-sequence-v1' " +
                "ORDER BY \"name\"",
        )

    private val REQUIRED_DMG_SEQUENCES_COLUMNS = setOf(
        "managed_by",
        "format_version",
        "name",
        "next_value",
        "last_returned_value",
        "exhausted",
        "increment_by",
        "min_value",
        "max_value",
        "cycle_enabled",
        "cache_size",
    )
}
