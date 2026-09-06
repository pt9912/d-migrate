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

    /**
     * Die Objekte, von denen eine View abhaengt, aufgeteilt nach dem, was
     * die Projektion belegen kann.
     *
     * [tables]/[views] fuehren nur Objekte **desselben Schemas** —
     * `ALL_DEPENDENCIES` liefert auch schemafremde Verweise (gemessen: eine
     * `SELECT 1 FROM dual`-View traegt `PUBLIC.DUAL` als `SYNONYM`), die im
     * neutralen Modell keine Entsprechung haben.
     *
     * Eine View, die im Ergebnis GAR NICHT vorkommt, ist etwas anderes als
     * eine mit leeren Listen: **gemessen (2026-09-06) traegt jede View
     * mindestens eine `ALL_DEPENDENCIES`-Zeile** — selbst die ueber `dual`.
     * Fehlt sie ganz, sieht der lesende Nutzer die Abhaengigkeiten nicht
     * (fehlende Rechte auf die referenzierten Objekte).
     *
     * [unmappedInSchema] zaehlt die Zeilen, die im eigenen Schema liegen,
     * aber weder `TABLE` noch `VIEW` sind — vor allem **Synonyme**, die
     * gemessen als eigener `referenced_type` auftreten. Ohne diese Zahl
     * liesse sich „referenziert wirklich nichts im Schema" nicht von
     * „referenziert eine Tabelle ueber ein Synonym" unterscheiden, und der
     * zweite Fall wuerde faelschlich als verifiziert leer gelten: der
     * Reprojector faende dann beim Rename nichts und liesse die Sicht still
     * invalid zurueck.
     */
    data class ViewDependencyRow(
        val tables: List<String>,
        val views: List<String>,
        val unmappedInSchema: Int,
    )

    data class UnreadObject(val type: String, val name: String)

    /** Identity-Spalte fuer den Datenpfad: Name, Erzeugungsmodus, Sequenzname, Increment. */
    data class IdentityColumnRow(
        val column: String,
        val generation: String,
        val sequenceName: String,
        val increment: Long,
    )

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

    /**
     * Benannte Sequenzen des Schemas -- **ohne** die Sequenzen hinter
     * IDENTITY-Spalten.
     *
     * Oracle fuehrt die identity-gestuetzte Sequenz (`ISEQ$$_n`) in
     * `ALL_SEQUENCES` wie jede andere. Sie ist aber kein Objekt, das ein
     * Anwender deklariert hat: sie entsteht mit der Spalte, verschwindet mit
     * ihr, und `ALTER SEQUENCE` fasst sie nicht an (`ORA-32793`). Ungefiltert
     * traegt jedes reverse-gelesene Schema mit IDENTITY-Spalte eine Sequenz,
     * die im Soll-Schema niemals steht -- der Vergleich meldete sie als
     * fehlend, und `schema migrate` plante ein `DROP SEQUENCE`, das Oracle
     * ohnehin ablehnt.
     *
     * PostgreSQL loest dasselbe ueber `pg_depend.deptype IN ('a','i')`; das
     * Oracle-Gegenstueck ist `ALL_TAB_IDENTITY_COLS.SEQUENCE_NAME`.
     */
    fun listSequences(session: JdbcOperations, schema: String): List<SequenceRow> =
        session.queryList(
            """
            SELECT s.sequence_name, s.last_number, s.increment_by, s.min_value, s.max_value,
                   s.cycle_flag, s.cache_size
            FROM all_sequences s
            WHERE s.sequence_owner = ?
              AND NOT EXISTS (
                  SELECT 1 FROM all_tab_identity_cols i
                  WHERE i.owner = s.sequence_owner AND i.sequence_name = s.sequence_name
              )
            ORDER BY s.sequence_name
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

    /**
     * View-Abhaengigkeiten aus `ALL_DEPENDENCIES`, je View gebuendelt.
     *
     * Die Sicht ist **objektgenau, nicht spaltengenau** — eine
     * spaltengranulare Quelle gibt es in Oracle nicht (gemessen: es existiert
     * kein `ALL_DEPENDENCY_COLUMNS`, und unter den `SYS`-Sichten mit
     * `DEPENDENC` im Namen ist keine spaltenbezogene). `DependencyInfo.columns`
     * bleibt fuer Oracle deshalb leer, und der dialektunabhaengige
     * `VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS`-Waechter des Planers greift —
     * dieselbe Lage wie bei MySQL.
     *
     * Der `referenced_owner`-Filter laeuft absichtlich NICHT in der
     * `WHERE`-Klausel: sonst liesse sich „keine Zeile im Schema" nicht von
     * „gar keine Zeile" (fehlende Rechte) unterscheiden.
     */
    fun listViewDependencies(session: JdbcOperations, schema: String): Map<String, ViewDependencyRow> {
        val rows = session.queryList(
            """
            SELECT name, referenced_owner, referenced_name, referenced_type
            FROM all_dependencies
            WHERE owner = ? AND type = 'VIEW'
            ORDER BY name, referenced_name
            """.trimIndent(),
            schema,
        )
        return rows.groupBy { it.string("name") }.mapValues { (_, viewRows) ->
            val inSchema = viewRows.filter { it.stringOrNull("referenced_owner") == schema }
            val tables = inSchema.filter { it.stringOrNull("referenced_type") == "TABLE" }
                .map { it.string("referenced_name") }
                .distinct()
            val views = inSchema.filter { it.stringOrNull("referenced_type") == "VIEW" }
                .map { it.string("referenced_name") }
                .distinct()
            ViewDependencyRow(
                tables = tables,
                views = views,
                unmappedInSchema = inSchema.size -
                    inSchema.count { it.stringOrNull("referenced_type") in MAPPED_REFERENCED_TYPES },
            )
        }
    }

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

    /** Identity-Spalten der Tabelle (Datenpfad: ALWAYS/BY-DEFAULT-Toggle, Reseed). */
    fun identityColumns(session: JdbcOperations, schema: String, table: String): List<IdentityColumnRow> =
        session.queryList(
            """
            SELECT ic.column_name, ic.generation_type, ic.sequence_name, s.increment_by
            FROM all_tab_identity_cols ic
            JOIN all_sequences s
                ON s.sequence_owner = ic.owner AND s.sequence_name = ic.sequence_name
            WHERE ic.owner = ? AND ic.table_name = ?
            """.trimIndent(),
            schema,
            table,
        ).map { row ->
            IdentityColumnRow(
                column = row.string("column_name"),
                generation = row.string("generation_type"),
                sequenceName = row.string("sequence_name"),
                increment = row.long("increment_by") ?: 1L,
            )
        }

    /** Virtuelle (`GENERATED ALWAYS AS (...) VIRTUAL`) Spalten -- der Import darf sie nicht schreiben. */
    fun virtualColumns(session: JdbcOperations, schema: String, table: String): Set<String> =
        session.queryList(
            """
            SELECT column_name
            FROM all_tab_cols
            WHERE owner = ? AND table_name = ? AND virtual_column = 'YES'
            """.trimIndent(),
            schema,
            table,
        ).mapNotNullTo(mutableSetOf()) { it["column_name"] as? String }

    /** `MAX(<column>)` der Tabelle; `null` bei leerer Tabelle. */
    fun maxValue(session: JdbcOperations, quotedTable: String, column: String): Long? =
        (
            session.querySingle(
                "SELECT MAX(${OracleIdentifiers.quote(column)}) AS max_value FROM $quotedTable",
            )?.get("max_value") as? Number
            )?.toLong()

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

    /** Die `referenced_type`-Werte, die im neutralen Modell eine Entsprechung haben. */
    private val MAPPED_REFERENCED_TYPES = setOf("TABLE", "VIEW")

    private fun Map<String, Any?>.string(key: String): String =
        requireNotNull(this[key] as? String) { "missing '$key' in catalog row" }

    /**
     * Fuer Katalogspalten, die NULL sein duerfen. `ALL_DEPENDENCIES
     * .REFERENCED_OWNER` ist nicht als NOT NULL deklariert; ein einziger
     * NULL-Wert wuerde ueber [string] den ganzen Reverse-Lauf abbrechen
     * statt die Zeile zu ignorieren.
     */
    private fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as? String

    private fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

    private fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()
}
