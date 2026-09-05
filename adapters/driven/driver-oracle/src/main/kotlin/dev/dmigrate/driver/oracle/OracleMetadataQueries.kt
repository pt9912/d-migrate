package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.metadata.ConstraintProjection
import dev.dmigrate.driver.metadata.ForeignKeyProjection
import dev.dmigrate.driver.metadata.IndexProjection
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.metadata.TableRef

/**
 * Katalog-Queries für den Oracle-Reverse-Read. `ALL_*`-Sichten (nicht
 * `USER_*`) mit explizitem `owner`-Filter, damit ein Aufrufer mit
 * weitreichenderen Grants nicht versehentlich andere Schemas sieht.
 * Recycle-Bin-Objekte (`BIN$...`) werden ausgeschlossen.
 */
internal object OracleMetadataQueries {

    data class ColumnRow(
        val name: String,
        val typeName: String,
        val length: Int?,
        val precision: Int?,
        val scale: Int?,
        val nullable: Boolean,
        val isIdentity: Boolean,
        val identityGeneration: String?,
        val identitySequenceName: String?,
        val defaultDefinition: String?,
        val ordinal: Int,
    )

    data class IndexScan(val indices: List<IndexProjection>)

    data class SequenceRow(
        val name: String,
        val lastNumber: Long,
        val increment: Long,
        val minValue: Long?,
        val maxValue: Long?,
        val cycle: Boolean,
        val cache: Int?,
    )

    data class ViewRow(val name: String, val text: String)

    data class UnreadObject(val type: String, val name: String)

    fun listTableRefs(session: JdbcOperations, schema: String): List<TableRef> =
        session.queryList(
            """
            SELECT table_name
            FROM all_tables
            WHERE owner = ? AND table_name NOT LIKE 'BIN${'$'}%'
            ORDER BY table_name
            """.trimIndent(),
            schema,
        ).map { row -> TableRef(name = row.string("table_name"), schema = schema) }

    fun listColumns(session: JdbcOperations, schema: String, table: String): List<ColumnRow> =
        session.queryList(
            """
            SELECT c.column_name, c.data_type, c.data_length, c.data_precision, c.data_scale,
                   c.nullable, c.column_id, c.data_default,
                   ic.generation_type AS identity_generation, ic.sequence_name AS identity_sequence
            FROM all_tab_columns c
            LEFT JOIN all_tab_identity_cols ic
                ON ic.owner = c.owner AND ic.table_name = c.table_name AND ic.column_name = c.column_name
            WHERE c.owner = ? AND c.table_name = ?
            ORDER BY c.column_id
            """.trimIndent(),
            schema,
            table,
        ).map { row ->
            ColumnRow(
                name = row.string("column_name"),
                typeName = row.string("data_type"),
                length = row.int("data_length"),
                precision = row.int("data_precision"),
                scale = row.int("data_scale"),
                nullable = row.string("nullable") == "Y",
                isIdentity = row["identity_generation"] != null,
                identityGeneration = row["identity_generation"] as? String,
                identitySequenceName = row["identity_sequence"] as? String,
                defaultDefinition = (row["data_default"] as? String)?.trim()?.ifEmpty { null },
                ordinal = row.int("column_id") ?: 0,
            )
        }

    fun listPrimaryKeyColumns(session: JdbcOperations, schema: String, table: String): List<String> =
        session.queryList(
            """
            SELECT cc.column_name
            FROM all_constraints con
            JOIN all_cons_columns cc
                ON cc.owner = con.owner AND cc.constraint_name = con.constraint_name
            WHERE con.owner = ? AND con.table_name = ? AND con.constraint_type = 'P'
            ORDER BY cc.position
            """.trimIndent(),
            schema,
            table,
        ).map { it.string("column_name") }

    fun listForeignKeys(session: JdbcOperations, schema: String, table: String): List<ForeignKeyProjection> {
        val rows = session.queryList(
            """
            SELECT fk.constraint_name, fkc.column_name, fkc.position,
                   rt.table_name AS referenced_table, rcc.column_name AS referenced_column,
                   fk.delete_rule
            FROM all_constraints fk
            JOIN all_cons_columns fkc
                ON fkc.owner = fk.owner AND fkc.constraint_name = fk.constraint_name
            JOIN all_constraints rt
                ON rt.owner = fk.r_owner AND rt.constraint_name = fk.r_constraint_name
            JOIN all_cons_columns rcc
                ON rcc.owner = rt.owner AND rcc.constraint_name = rt.constraint_name
                    AND rcc.position = fkc.position
            WHERE fk.owner = ? AND fk.table_name = ? AND fk.constraint_type = 'R'
            ORDER BY fk.constraint_name, fkc.position
            """.trimIndent(),
            schema,
            table,
        )
        return rows.groupBy { it.string("constraint_name") }.map { (name, group) ->
            ForeignKeyProjection(
                name = name,
                columns = group.map { it.string("column_name") },
                referencedTable = group.first().string("referenced_table"),
                referencedColumns = group.map { it.string("referenced_column") },
                onDelete = deleteRuleToAction(group.first()["delete_rule"] as? String),
                // Oracle kennt kein ON UPDATE fuer Fremdschluessel.
                onUpdate = null,
            )
        }
    }

    /**
     * Indizes ohne die, die bereits die PK-Constraint tragen
     * (`ALL_CONSTRAINTS.INDEX_NAME` -- Oracles Aequivalent zu MSSQLs
     * `is_primary_key`-Flag). UNIQUE-Constraint-Indizes bleiben ABSICHTLICH
     * erhalten (anders als PK): es gibt keine gesonderte Oracle-Abfrage fuer
     * UNIQUE-Constraints, `singleColumnUniqueFromIndices`/
     * `buildMultiColumnUniqueFromIndices` heben sie aus genau diesem Scan.
     */
    fun scanIndexes(session: JdbcOperations, schema: String, table: String): IndexScan {
        val primaryKeyIndexNames = session.queryList(
            """
            SELECT index_name
            FROM all_constraints
            WHERE owner = ? AND table_name = ? AND constraint_type = 'P' AND index_name IS NOT NULL
            """.trimIndent(),
            schema,
            table,
        ).mapNotNull { it["index_name"] as? String }.toSet()

        val rows = session.queryList(
            """
            SELECT i.index_name, i.uniqueness, ic.column_name, ic.column_position, ic.descend
            FROM all_indexes i
            JOIN all_ind_columns ic
                ON ic.index_owner = i.owner AND ic.index_name = i.index_name
            WHERE i.owner = ? AND i.table_name = ?
            ORDER BY i.index_name, ic.column_position
            """.trimIndent(),
            schema,
            table,
        )
        val indices = rows.groupBy { it.string("index_name") }
            .filterKeys { it !in primaryKeyIndexNames }
            .map { (name, group) ->
                IndexProjection(
                    name = name,
                    columns = group.map { it.string("column_name") },
                    isUnique = group.first().string("uniqueness") == "UNIQUE",
                    directions = group.map { row ->
                        if (row["descend"] as? String == "DESC") {
                            dev.dmigrate.core.model.IndexSortDirection.DESC
                        } else {
                            null
                        }
                    },
                )
            }
        return IndexScan(indices = indices)
    }

    /**
     * CHECK-Constraints ohne die von Oracle implizit fuer jede NOT-NULL-
     * Spalte erzeugten (`"COL" IS NOT NULL`) -- sonst erschiene jede
     * NOT-NULL-Spalte zusaetzlich als explizite CHECK-Constraint.
     */
    fun listCheckConstraints(session: JdbcOperations, schema: String, table: String): List<ConstraintProjection> =
        session.queryList(
            """
            SELECT constraint_name, search_condition_vc
            FROM all_constraints
            WHERE owner = ? AND table_name = ? AND constraint_type = 'C'
              AND generated = 'USER NAME'
            ORDER BY constraint_name
            """.trimIndent(),
            schema,
            table,
        ).mapNotNull { row ->
            val expr = row["search_condition_vc"] as? String ?: return@mapNotNull null
            if (IMPLICIT_NOT_NULL_CHECK.matches(expr.trim())) return@mapNotNull null
            ConstraintProjection(
                name = row.string("constraint_name"),
                type = "CHECK",
                expression = expr.trim(),
            )
        }

    fun listSequences(session: JdbcOperations, schema: String): List<SequenceRow> =
        session.queryList(
            """
            SELECT sequence_name, last_number, increment_by, min_value, max_value,
                   cycle_flag, cache_size
            FROM all_sequences
            WHERE sequence_owner = ?
            ORDER BY sequence_name
            """.trimIndent(),
            schema,
        ).map { row ->
            SequenceRow(
                name = row.string("sequence_name"),
                lastNumber = row.long("last_number") ?: 1L,
                increment = row.long("increment_by") ?: 1L,
                minValue = row.long("min_value"),
                maxValue = row.long("max_value"),
                cycle = row.string("cycle_flag") == "Y",
                cache = row.int("cache_size")?.takeIf { it > 0 },
            )
        }

    /** `ALL_VIEWS.TEXT` ist bereits der reine SELECT-Text -- kein CREATE-VIEW-Wrapper. */
    fun listViews(session: JdbcOperations, schema: String): List<ViewRow> =
        session.queryList(
            """
            SELECT view_name, text
            FROM all_views
            WHERE owner = ?
            ORDER BY view_name
            """.trimIndent(),
            schema,
        ).map { row -> ViewRow(name = row.string("view_name"), text = row.string("text")) }

    /** Routinen/Trigger im Schema, die der Slice-1-Reader nicht liest. */
    fun listUnreadObjects(session: JdbcOperations, schema: String): List<UnreadObject> =
        session.queryList(
            """
            SELECT object_type, object_name
            FROM all_objects
            WHERE owner = ? AND object_type IN ('PROCEDURE', 'FUNCTION', 'TRIGGER', 'PACKAGE')
            ORDER BY object_type, object_name
            """.trimIndent(),
            schema,
        ).map { row -> UnreadObject(type = row.string("object_type"), name = row.string("object_name")) }

    private fun deleteRuleToAction(rule: String?): String? = when (rule) {
        "CASCADE" -> "CASCADE"
        "SET NULL" -> "SET NULL"
        "NO ACTION" -> "NO ACTION"
        else -> null
    }

    // Oracle generiert diese Form woertlich fuer jede NOT-NULL-Spalte; ein
    // gleichlautender expliziter Check waere davon nicht unterscheidbar
    // (seltener Grenzfall, dokumentiert statt verschwiegen).
    private val IMPLICIT_NOT_NULL_CHECK = Regex("""(?i)^"?[A-Za-z0-9_$#]+"?\s+IS\s+NOT\s+NULL$""")

    private fun Map<String, Any?>.string(key: String): String =
        requireNotNull(this[key] as? String) { "missing '$key' in catalog row" }

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
}
