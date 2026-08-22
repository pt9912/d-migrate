package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.inOrdinalOrder

/**
 * SQL Server lehnt Fremdschlüssel mit kaskadierender Aktion (`CASCADE`,
 * `SET NULL`, `SET DEFAULT`) ab, wenn sie einen Kaskaden-Zyklus oder einen
 * zweiten Kaskadenpfad zwischen zwei Tabellen eröffnen würden (Fehler 1785,
 * „may cause cycles or multiple cascade paths") — PostgreSQL/MySQL erlauben
 * beides. Der Wächter spielt die FKs in Schema-Reihenfolge durch und merkt
 * sich, welche Constraints ihre Kaskade verlieren müssen; der Generator
 * rendert sie mit `NO ACTION` und weist das als E057 aus.
 *
 * Kanten laufen vom referenzierten Elternteil zum Kind (die Richtung, in der
 * eine Kaskade wirkt). Für den Zyklus-/Mehrfachpfad-Test zählen Delete- und
 * Update-Kaskaden gemeinsam — konservativ, weil SQL Server beide Pfadmengen
 * prüft.
 */
internal class MssqlCascadePathGuard private constructor(private val neutralised: Set<String>) {

    /** `true`, wenn die kaskadierenden Aktionen dieses Constraints zu `NO ACTION` werden müssen. */
    fun mustNeutralise(constraintName: String): Boolean = constraintName in neutralised

    companion object {
        val NONE = MssqlCascadePathGuard(emptySet())

        fun isCascading(action: ReferentialAction?): Boolean =
            action == ReferentialAction.CASCADE || action == ReferentialAction.SET_NULL ||
                action == ReferentialAction.SET_DEFAULT

        fun analyse(schema: SchemaDefinition): MssqlCascadePathGuard {
            val edges = mutableMapOf<String, MutableSet<String>>()
            val neutralised = mutableSetOf<String>()
            for ((tableName, table) in schema.tables) {
                for ((colName, col) in table.columns.inOrdinalOrder()) {
                    val ref = col.references ?: continue
                    consider("fk_${tableName}_$colName", tableName, ref.table, ref.onDelete, ref.onUpdate, edges, neutralised)
                }
                for (constraint in table.constraints) {
                    if (constraint.type != ConstraintType.FOREIGN_KEY) continue
                    val ref = constraint.references ?: continue
                    consider(constraint.name, tableName, ref.table, ref.onDelete, ref.onUpdate, edges, neutralised)
                }
            }
            return MssqlCascadePathGuard(neutralised)
        }

        private fun consider(
            name: String,
            child: String,
            parent: String,
            onDelete: ReferentialAction?,
            onUpdate: ReferentialAction?,
            edges: MutableMap<String, MutableSet<String>>,
            neutralised: MutableSet<String>,
        ) {
            if (!isCascading(onDelete) && !isCascading(onUpdate)) return
            val cycle = child == parent || reaches(edges, child, parent)
            val secondPath = ancestorsOrSelf(edges, parent).any { reaches(edges, it, child) }
            if (cycle || secondPath) {
                neutralised += name
                return
            }
            edges.getOrPut(parent) { mutableSetOf() } += child
        }

        private fun reaches(edges: Map<String, Set<String>>, from: String, to: String): Boolean {
            val seen = mutableSetOf<String>()
            val stack = ArrayDeque<String>().apply { add(from) }
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (!seen.add(node)) continue
                for (next in edges[node].orEmpty()) {
                    if (next == to) return true
                    stack.add(next)
                }
            }
            return false
        }

        private fun ancestorsOrSelf(edges: Map<String, Set<String>>, node: String): Set<String> =
            edges.keys.filterTo(mutableSetOf()) { reaches(edges, it, node) }.apply { add(node) }
    }
}
