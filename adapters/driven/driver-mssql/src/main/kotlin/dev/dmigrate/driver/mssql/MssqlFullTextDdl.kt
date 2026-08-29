package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.TableDefinition

/**
 * Volltext-Indizes fuer SQL Server.
 *
 * SQL Server braucht dafuer zwei Dinge, die das neutrale Modell nicht traegt:
 * einen **Volltext-Katalog** als eigenstaendiges Datenbankobjekt und einen
 * **Schluesselindex**, ueber den der Volltext-Index die Zeilen adressiert.
 *
 * ```sql
 * CREATE FULLTEXT CATALOG [ftc_docs];
 * CREATE FULLTEXT INDEX ON [docs] ([body]) KEY INDEX [pk_docs] ON [ftc_docs];
 * ```
 *
 * Die Regeln stammen aus Messungen am Server, nicht aus der Dokumentation:
 *
 * - Der Schluesselindex muss **einspaltig, eindeutig und nicht nullbar** sein.
 *   Ein zusammengesetzter Primaerschluessel wird abgelehnt, ein nullbarer
 *   UNIQUE-Constraint ebenso.
 * - Eine Tabelle traegt **hoechstens einen** Volltext-Index; er darf aber
 *   mehrere Spalten umfassen.
 * - `DROP TABLE` entfernt den Volltext-Index mit, **nicht** den Katalog.
 */
internal object MssqlFullTextDdl {

    /** Katalogname je Tabelle. */
    fun catalogName(table: String): String = "ftc_$table"

    /** Das Urteil ueber die Volltext-Indizes einer Tabelle. */
    sealed interface Verdict {
        /** Renderbar, mit diesem Schluesselindex. */
        data class Renderable(val keyIndexName: String) : Verdict

        /** Kein einspaltiger, eindeutiger, nicht nullbarer Index vorhanden. */
        data object NoKeyIndex : Verdict

        /** Mehr als ein Volltext-Index; SQL Server erlaubt genau einen je Tabelle. */
        data class MoreThanOne(val count: Int) : Verdict
    }

    fun verdict(tableName: String, table: TableDefinition): Verdict {
        val fullTextCount = table.indices.count { it.type == IndexType.FULLTEXT }
        if (fullTextCount > 1) return Verdict.MoreThanOne(fullTextCount)
        return keyIndexName(tableName, table)?.let { Verdict.Renderable(it) } ?: Verdict.NoKeyIndex
    }

    /**
     * Der Name des Index, der als `KEY INDEX` taugt.
     *
     * Reihenfolge ist Absicht: der Primaerschluessel zuerst, weil er der
     * erwartete Fall ist und seinen Namen aus [MssqlConstraintNames] bekommt.
     * Danach benannte UNIQUE-Constraints und unique Indizes. Ein Kandidat zaehlt
     * nur, wenn seine Spalte auch **not null** ist — der Server prueft das, und
     * eine Tabelle kann einen einspaltigen UNIQUE auf einer nullbaren Spalte
     * haben.
     */
    private fun keyIndexName(tableName: String, table: TableDefinition): String? {
        table.primaryKey.singleOrNull()?.let { pkColumn ->
            if (isNotNull(table, pkColumn)) return MssqlConstraintNames.primaryKey(tableName)
        }
        table.constraints
            .firstOrNull { c ->
                c.type == ConstraintType.UNIQUE &&
                    c.columns?.singleOrNull()?.let { isNotNull(table, it) } == true
            }
            ?.let { return it.name }
        table.columns.entries
            .firstOrNull { (_, column) -> column.unique && column.required }
            ?.let { return MssqlConstraintNames.unique(tableName, it.key) }
        table.indices
            .firstOrNull { idx ->
                idx.unique && idx.name != null &&
                    idx.columns.singleOrNull()?.name?.let { isNotNull(table, it) } == true
            }
            ?.let { return it.name }
        return null
    }

    /**
     * Der Primaerschluessel macht seine Spalten implizit `NOT NULL`, auch wenn
     * das Modell sie nicht so markiert — deshalb zaehlt er hier mit.
     */
    private fun isNotNull(table: TableDefinition, column: String): Boolean =
        table.columns[column]?.required == true || column in table.primaryKey

    /**
     * Katalog und Index in **einem** Statement. Am Server gemessen: beide
     * duerfen in demselben Batch stehen. Ein Statement je Index haelt ausserdem
     * alle Aufrufstellen unveraendert, die genau eines erwarten.
     */
    fun createStatement(
        tableName: String,
        columns: List<String>,
        keyIndexName: String,
        quote: (String) -> String,
    ): String {
        val catalog = quote(catalogName(tableName))
        val columnList = columns.joinToString(", ") { quote(it) }
        return "CREATE FULLTEXT CATALOG $catalog;\n" +
            "CREATE FULLTEXT INDEX ON ${quote(tableName)} ($columnList) " +
            "KEY INDEX ${quote(keyIndexName)} ON $catalog;"
    }

    /**
     * Der Rueckbau. `DROP TABLE` nimmt den Index mit, den Katalog aber nicht —
     * am Server gemessen (er blieb nach dem Tabellen-Drop stehen).
     */
    fun dropStatements(tableName: String, quote: (String) -> String): List<String> = listOf(
        "DROP FULLTEXT INDEX ON ${quote(tableName)};",
        "DROP FULLTEXT CATALOG ${quote(catalogName(tableName))};",
    )
}
