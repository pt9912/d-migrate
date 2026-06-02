package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTrigger

/**
 * E.2 Sub-Slice A.1 pre-plan gate: surfaces trigger-name ambiguity
 * before `SchemaDefinition.triggers: Map<String, TriggerDefinition>`
 * is materialised.
 *
 * The neutral schema model keys triggers by name only, so two distinct
 * triggers that share a name but belong to different tables would
 * silently collapse to one entry at `.toMap()`. This detector runs on
 * the raw `List<NamedTrigger>` before that step (reader output, raw
 * parser output) and reports each `(name, [tableA, tableB, ...])`
 * tuple it finds.
 *
 * Callers (live-DB schema readers, schema-codec post-processing) MUST
 * consult the detector before building the trigger map; on
 * [TriggerNameCollisionOutcome.Collisions] they must propagate a
 * `TRIGGER_NAME_COLLISION` blocker and stop.
 *
 * The full structural fix — keying triggers by
 * `ObjectKeyCodec.triggerKey(table, name)` so the model can hold
 * genuine `(name, table)` ambiguity — is an F.4 RenameTrigger
 * pre-condition and is deliberately not part of E.2.
 */
object TriggerNameCollisionDetector {

    fun detect(triggers: List<NamedTrigger>): TriggerNameCollisionOutcome {
        if (triggers.isEmpty()) return TriggerNameCollisionOutcome.Ok

        val tablesByName = LinkedHashMap<String, MutableList<String>>()
        for (entry in triggers) {
            val tablesForName = tablesByName.getOrPut(entry.name) { mutableListOf() }
            if (entry.definition.table !in tablesForName) {
                tablesForName += entry.definition.table
            }
        }

        val collisions = tablesByName
            .filterValues { it.size > 1 }
            .map { (name, tables) -> TriggerNameCollision(name, tables.toList()) }

        return if (collisions.isEmpty()) {
            TriggerNameCollisionOutcome.Ok
        } else {
            TriggerNameCollisionOutcome.Collisions(collisions)
        }
    }
}

/**
 * Outcome of a [TriggerNameCollisionDetector.detect] call.
 *
 * [Ok] means the input held at most one table per trigger name and
 * the caller may proceed to materialise the trigger map. [Collisions]
 * carries one entry per colliding trigger name; the caller must raise
 * a `TRIGGER_NAME_COLLISION` blocker and not build the map.
 */
sealed interface TriggerNameCollisionOutcome {
    data object Ok : TriggerNameCollisionOutcome
    data class Collisions(val collisions: List<TriggerNameCollision>) : TriggerNameCollisionOutcome
}

/**
 * One trigger name that appears on two or more distinct tables in the
 * detector's input.
 */
data class TriggerNameCollision(
    val name: String,
    val tables: List<String>,
) {
    init {
        require(tables.size >= 2) {
            "TriggerNameCollision must carry at least two distinct tables, got $tables"
        }
    }
}
