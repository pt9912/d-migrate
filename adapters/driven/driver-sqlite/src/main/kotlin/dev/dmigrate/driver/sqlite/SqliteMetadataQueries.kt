package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.metadata.*

/**
 * VA4/5d Befund 3: one SpatiaLite `geometry_columns` registry row, reduced to the
 * two facts the neutral reverse path cannot recover from `PRAGMA table_info`/
 * `sqlite_master` alone: the [srid] (null = unconstrained, mirrors PG/MySQL VA2)
 * and whether an R*Tree [spatialIndexEnabled] spatial index backs the column.
 */
data class SqliteGeometryColumn(
    val table: String,
    val column: String,
    val srid: Int?,
    val spatialIndexEnabled: Boolean,
)

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
        val rows = session.queryList("PRAGMA table_info(${SqlIdentifiers.quoteStringLiteral(table, DatabaseDialect.SQLITE)})")
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
        val rows = session.queryList("PRAGMA table_info(${SqlIdentifiers.quoteStringLiteral(table, DatabaseDialect.SQLITE)})")
        return rows.filter { (it["pk"] as Number).toInt() > 0 }
            .sortedBy { (it["pk"] as Number).toInt() }
            .map { it["name"] as String }
    }

    fun listForeignKeys(session: JdbcMetadataSession, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList("PRAGMA foreign_key_list(${SqlIdentifiers.quoteStringLiteral(table, DatabaseDialect.SQLITE)})")
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
        val indexRows = session.queryList("PRAGMA index_list(${SqlIdentifiers.quoteStringLiteral(table, DatabaseDialect.SQLITE)})")
        return indexRows.mapNotNull { idx ->
            val indexName = idx["name"] as String
            // Skip SQLite autoindex (backing indices for PK/UNIQUE constraints) —
            // UNIQUE-constraint autoindexes are surfaced separately via
            // [listUniqueConstraintIndexes] and fold onto columns/constraints.
            if (indexName.startsWith("sqlite_autoindex_")) return@mapNotNull null
            val colRows = keyColumnRows(session, indexName)
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

    /**
     * AP4 (postcompare-type-canonicalization slice): the `sqlite_autoindex_*`
     * entries backing inline UNIQUE constraints (PRAGMA index_list
     * `origin = 'u'`). They are NOT user indices — the reader folds them onto
     * the column `unique` flag (single-column) or reconstructs the named
     * constraint (multi-column, name via [SqliteUniqueConstraintScanner]).
     * PK autoindexes (`origin = 'pk'`) stay excluded.
     */
    fun listUniqueConstraintIndexes(session: JdbcMetadataSession, table: String): List<IndexProjection> {
        val indexRows = session.queryList("PRAGMA index_list(${SqlIdentifiers.quoteStringLiteral(table, DatabaseDialect.SQLITE)})")
        return indexRows.mapNotNull { idx ->
            if ((idx["origin"] as? String) != "u") return@mapNotNull null
            val indexName = idx["name"] as String
            val cols = keyColumnRows(session, indexName).mapNotNull { it["name"] as? String }
            if (cols.isEmpty()) return@mapNotNull null
            IndexProjection(name = indexName, columns = cols, isUnique = true)
        }
    }

    /** Key columns of an index (PRAGMA index_xinfo, `key = 1`, seqno-sortiert). */
    private fun keyColumnRows(session: JdbcMetadataSession, indexName: String): List<Map<String, Any?>> =
        session.queryList("PRAGMA index_xinfo(${SqlIdentifiers.quoteStringLiteral(indexName, DatabaseDialect.SQLITE)})")
            .filter { ((it["key"] as? Number)?.toInt() ?: 1) == 1 }
            .sortedBy { (it["seqno"] as Number).toInt() }

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

    /**
     * VA4/5d Befund 3: read the SpatiaLite `geometry_columns` registry so the
     * reverse path can recover the SRID (lost via `PRAGMA table_info`, which only
     * yields the declared geometry subtype) and the spatial-index flag (the R*Tree
     * index is a virtual table, not a `sqlite_master` `type='index'` row).
     *
     * Guarded: a non-SpatiaLite database has no `geometry_columns` table, so the
     * query throws and we return an empty list (= "no SpatiaLite metadata"). The
     * extension need not be loaded — `geometry_columns` is a plain table once
     * `InitSpatialMetaData()` has run.
     */
    fun listGeometryColumns(session: JdbcMetadataSession): List<SqliteGeometryColumn> = try {
        session.queryList(
            "SELECT f_table_name, f_geometry_column, srid, spatial_index_enabled " +
                "FROM geometry_columns",
        ).mapNotNull { row ->
            val table = row["f_table_name"] as? String ?: return@mapNotNull null
            val column = row["f_geometry_column"] as? String ?: return@mapNotNull null
            SqliteGeometryColumn(
                table = table,
                column = column,
                srid = (row["srid"] as? Number)?.toInt()?.takeIf { it > 0 },
                spatialIndexEnabled = ((row["spatial_index_enabled"] as? Number)?.toInt() ?: 0) == 1,
            )
        }
    } catch (_: Exception) {
        emptyList()
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
