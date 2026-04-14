package dev.dmigrate.core.diff

import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming

data class NamedTrigger(val name: String, val definition: TriggerDefinition)

data class TriggerDiff(
    val name: String,
    val table: ValueChange<String>? = null,
    val event: ValueChange<TriggerEvent>? = null,
    val timing: ValueChange<TriggerTiming>? = null,
    val forEach: ValueChange<TriggerForEach>? = null,
    val condition: ValueChange<String?>? = null,
    val body: ValueChange<String?>? = null,
    val sourceDialect: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean = table != null || event != null || timing != null ||
        forEach != null || condition != null || body != null || sourceDialect != null
}
