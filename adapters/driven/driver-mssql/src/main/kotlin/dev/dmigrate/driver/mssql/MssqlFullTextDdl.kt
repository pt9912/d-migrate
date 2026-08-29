package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

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
 * Es gelten drei Regeln:
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

    fun verdict(
        tableName: String,
        table: TableDefinition,
        fullTextIndex: IndexDefinition,
        lobColumns: Set<String>,
    ): Verdict {
        val fullTextCount = table.indices.count { it.type == IndexType.FULLTEXT }
        if (fullTextCount > 1) return Verdict.MoreThanOne(fullTextCount)
        return keyIndexName(tableName, table, fullTextIndex, lobColumns)
            ?.let { Verdict.Renderable(it) } ?: Verdict.NoKeyIndex
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
    private fun keyIndexName(
        tableName: String,
        table: TableDefinition,
        fullTextIndex: IndexDefinition,
        lobColumns: Set<String>,
    ): String? {
        table.primaryKey.singleOrNull()?.let { pkColumn ->
            if (isNotNull(table, pkColumn) && pkColumn !in lobColumns) {
                return MssqlConstraintNames.primaryKey(tableName)
            }
        }
        table.constraints
            .firstOrNull { c ->
                c.type == ConstraintType.UNIQUE && c.columns?.singleOrNull()
                    ?.let { isNotNull(table, it) && it !in lobColumns } == true
            }
            ?.let { return it.name }
        table.columns.entries
            .firstOrNull { (name, column) -> column.unique && column.required && name !in lobColumns }
            ?.let { return MssqlConstraintNames.unique(tableName, it.key) }
        // Ein separat gerenderter Index taugt nur, wenn er VOR dem Volltext-Index
        // steht — sonst verweist `KEY INDEX` auf etwas, das erst danach entsteht.
        val fullTextPosition = table.indices.indexOf(fullTextIndex)
        table.indices
            .filterIndexed { position, _ -> fullTextPosition < 0 || position < fullTextPosition }
            .firstOrNull { idx ->
                idx.unique && idx.name != null && !idx.clustered &&
                    idx.columns.singleOrNull()?.name
                        ?.let { isNotNull(table, it) && it !in lobColumns } == true
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
     * Katalog und Index in **einem** Statement; beide duerfen in demselben
     * Batch stehen. Das haelt alle Aufrufstellen unveraendert, die genau ein
     * Statement je Index erwarten.
     */
    fun createStatement(
        tableName: String,
        columns: List<String>,
        keyIndexName: String,
        quote: (String) -> String,
    ): String {
        val catalog = quote(catalogName(tableName))
        val columnList = columns.joinToString(", ") { quote(it) }
        // Bedingt, weil der Katalog `DROP TABLE` ueberlebt: der Tabellen-Neubau
        // legt die Tabelle neu an, und ein unbedingtes CREATE scheiterte dort
        // am schon vorhandenen Namen.
        return "IF NOT EXISTS (SELECT 1 FROM sys.fulltext_catalogs WHERE name = " +
            "${SqlIdentifiers.quoteStringLiteral(catalogName(tableName), DatabaseDialect.MSSQL)}) CREATE FULLTEXT CATALOG $catalog;\n" +
            "CREATE FULLTEXT INDEX ON ${quote(tableName)} ($columnList) " +
            "KEY INDEX ${quote(keyIndexName)} ON $catalog;"
    }

    /**
     * Der Rueckbau. `DROP TABLE` nimmt den Index mit, den Katalog aber nicht.
     */
    fun dropStatements(tableName: String, quote: (String) -> String): List<String> = listOf(
        // Beide bedingt, symmetrisch zum bedingten Anlegen: das DOWN eines
        // AddIndex loeschte sonst einen Katalog, den das UP wegen seines
        // Waechters gar nicht angelegt hat.
        "IF EXISTS (SELECT 1 FROM sys.fulltext_indexes WHERE object_id = " +
            "OBJECT_ID(${SqlIdentifiers.quoteStringLiteral(tableName, DatabaseDialect.MSSQL)})) " +
            "DROP FULLTEXT INDEX ON ${quote(tableName)};",
        "IF EXISTS (SELECT 1 FROM sys.fulltext_catalogs WHERE name = " +
            "${SqlIdentifiers.quoteStringLiteral(catalogName(tableName), DatabaseDialect.MSSQL)}) " +
            "DROP FULLTEXT CATALOG ${quote(catalogName(tableName))};",
    )
}
