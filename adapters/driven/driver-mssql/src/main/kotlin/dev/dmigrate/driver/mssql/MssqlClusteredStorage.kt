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

    /**
     * Mehr als ein Index beansprucht die Ablage. SQL Server erlaubt genau einen
     * clustered Index je Tabelle; welcher gemeint ist, kann das Werkzeug nicht
     * raten, und ein `CREATE CLUSTERED INDEX` auf den zweiten scheiterte mit
     * Msg 1902. Der Fall ist im neutralen Modell ausdrueckbar, in T-SQL nicht.
     */
    fun hasConflictingClaims(indices: List<IndexDefinition>?): Boolean =
        indices.orEmpty().count { it.clustered } > 1

    /** Beansprucht in diesem Indexsatz einer die Ablage? */
    fun claimedByIndex(indices: List<IndexDefinition>?): Boolean = indices.orEmpty().any { it.clustered }

    /**
     * Wechselt die Ablage von einer Tabelle die Ablage-Zustaendigkeit, ist der
     * Primaerschluessel mitbetroffen -- und zwar VOR dem Index, der sie
     * uebernimmt, beziehungsweise NACH dem, der sie abgibt.
     *
     * Der Grund ist die Reihenfolge, die T-SQL erzwingt: solange der clustered
     * Primaerschluessel steht, scheitert jedes `CREATE CLUSTERED INDEX` mit
     * Msg 1902. Umgekehrt kann der Primaerschluessel die Ablage erst
     * zurueckbekommen, wenn kein Index sie mehr haelt.
     *
     * `null` heisst: an der Zustaendigkeit aendert sich nichts, es ist nichts zu
     * tun. Das ist der Normalfall -- die allermeisten Index-Operationen fassen
     * die Ablage nicht an.
     */
    fun flip(before: List<IndexDefinition>?, after: List<IndexDefinition>?): Flip? {
        val heldBefore = claimedByIndex(before)
        val heldAfter = claimedByIndex(after)
        return when {
            heldBefore == heldAfter -> null
            heldAfter -> Flip.ToNonclustered
            else -> Flip.ToClustered
        }
    }

    /** Wohin der Primaerschluessel wechselt -- und wann relativ zur Index-Operation. */
    enum class Flip {
        /** Vor dem `CREATE CLUSTERED INDEX`: der Schluessel gibt die Ablage ab. */
        ToNonclustered,

        /** Nach dem `DROP INDEX`: der Schluessel bekommt sie zurueck. */
        ToClustered,
    }
}
