package dev.dmigrate.driver.sqlite

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
        val rows = session.queryList("PRAGMA table_info('${escapeSql(table)}')")
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
        val rows = session.queryList("PRAGMA table_info('${escapeSql(table)}')")
        return rows.filter { (it["pk"] as Number).toInt() > 0 }
            .sortedBy { (it["pk"] as Number).toInt() }
            .map { it["name"] as String }
    }

    fun listForeignKeys(session: JdbcMetadataSession, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList("PRAGMA foreign_key_list('${escapeSql(table)}')")
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
        val indexRows = session.queryList("PRAGMA index_list('${escapeSql(table)}')")
        return indexRows.mapNotNull { idx ->
            val indexName = idx["name"] as String
            // Skip SQLite autoindex (backing indices for PK/UNIQUE constraints)
            if (indexName.startsWith("sqlite_autoindex_")) return@mapNotNull null
            val colRows = session.queryList("PRAGMA index_info('${escapeSql(indexName)}')")
            val cols = colRows.sortedBy { (it["seqno"] as Number).toInt() }
                .mapNotNull { it["name"] as? String }
            if (cols.isEmpty()) return@mapNotNull null
            IndexProjection(
                name = indexName,
                columns = cols,
                isUnique = (idx["unique"] as Number).toInt() == 1,
            )
        }
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

    /** Get the CREATE TABLE SQL for a table. */
    fun getCreateSql(session: JdbcMetadataSession, table: String): String? {
        val row = session.querySingle(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", table
        )
        return row?.get("sql") as? String
    }

    private fun escapeSql(s: String) = s.replace("'", "''")
}
