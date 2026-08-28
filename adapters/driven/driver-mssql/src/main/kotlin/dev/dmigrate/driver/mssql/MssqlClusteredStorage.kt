package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.TableDefinition

/**
 * Wer traegt die Ablage der Tabelle.
 *
 * SQL Server erlaubt genau einen clustered Index je Tabelle, und ohne
 * gegenteilige Angabe bekommt ihn der Primaerschluessel. Das neutrale Modell
 * traegt `clustered` nur am Index -- die Aussage ueber den Primaerschluessel
 * wird daraus hergeleitet, statt sie ein zweites Mal zu fuehren, wo sie mit der
 * ersten driften koennte ([ADR 0049]).
 *
 * Ohne die Herleitung erzeugte der Generate-Pfad DDL, die der Server ablehnt:
 * ein `CREATE CLUSTERED INDEX` neben einem clustered Primaerschluessel
 * scheitert mit Msg 1902, "Cannot create more than one clustered index".
 */
internal object MssqlClusteredStorage {

    /**
     * Die Klausel fuer den Primaerschluessel dieser Tabelle -- `PRIMARY KEY`
     * oder `PRIMARY KEY NONCLUSTERED`, wenn ein Index die Ablage beansprucht.
     */
    fun primaryKeyClause(indices: List<IndexDefinition>): String =
        if (indices.any { it.clustered }) "PRIMARY KEY NONCLUSTERED" else "PRIMARY KEY"

    fun primaryKeyClause(table: TableDefinition): String = primaryKeyClause(table.indices)
}
