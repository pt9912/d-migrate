package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.metadata.ConstraintProjection
import dev.dmigrate.driver.metadata.ForeignKeyProjection
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Katalog-Queries fuer die **Constraints** einer Oracle-Tabelle:
 * Primaerschluessel, Fremdschluessel, UNIQUE und CHECK.
 *
 * Aus [OracleMetadataQueries] herausgeloest, weil sie eine eigene Frage
 * beantworten — nicht „wie sieht diese Tabelle aus", sondern „was sichert sie
 * zu". Sie teilen sich dieselbe Quelle (`all_constraints` mit
 * `all_cons_columns`) und dieselbe Unterscheidung, auf die es dabei ankommt:
 * ein Constraint ist etwas anderes als der Index, den Oracle dafuer anlegt.
 * Nur den Constraint baut `ALTER TABLE … DROP CONSTRAINT` ab.
 *
 * `ALL_*`-Sichten mit explizitem `owner`-Filter, wie nebenan.
 */
internal object OracleConstraintQueries {

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
     * CHECK-Constraints ohne die von Oracle implizit fuer jede NOT-NULL-
     * Spalte erzeugten (`"COL" IS NOT NULL`) -- sonst erschiene jede
     * NOT-NULL-Spalte zusaetzlich als explizite CHECK-Constraint.
     */
    /**
     * Die UNIQUE-**Constraints** der Tabelle, Name -> Spalten.
     *
     * Aus `all_constraints`, nicht aus der Indexliste: Oracle legt fuer einen
     * UNIQUE-Constraint einen Index an, aber ein gewoehnlicher Unique-Index ist
     * kein Constraint — und nur einen Constraint baut `DROP CONSTRAINT` ab.
     *
     * Der Name kann `SYS_C…` lauten, wenn der Constraint inline deklariert
     * wurde. Das ist kein Grund, ihn wegzulassen: er ist der Name, unter dem
     * die Datenbank ihn fuehrt, und genau den braucht ein Abbau.
     */
    fun listUniqueConstraintColumns(
        session: JdbcOperations,
        schema: String,
        table: String,
    ): Map<String, List<String>> =
        session.queryList(
            """
            SELECT con.constraint_name, col.column_name
            FROM all_constraints con
            JOIN all_cons_columns col
              ON col.owner = con.owner AND col.constraint_name = con.constraint_name
            WHERE con.owner = ? AND con.table_name = ? AND con.constraint_type = 'U'
            ORDER BY con.constraint_name, col.position
            """.trimIndent(),
            schema,
            table,
        ).groupBy({ it.string("constraint_name") }, { it.string("column_name") })
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
}
