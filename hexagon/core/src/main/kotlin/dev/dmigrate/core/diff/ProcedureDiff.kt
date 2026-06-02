package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.RoutineSecurity

data class NamedProcedure(val name: String, val definition: ProcedureDefinition)

data class ProcedureDiff(
    val name: String,
    val parameters: ValueChange<List<ParameterDefinition>>? = null,
    val language: ValueChange<String?>? = null,
    val body: ValueChange<String?>? = null,
    val sourceDialect: ValueChange<String?>? = null,
    /**
     * E.1 Routine-Migration Slice B: procedures gain the same routine
     * identity attribute diff fields that Slice A added for functions.
     * Any divergence triggers a `ReplaceProcedure` even when the body
     * hash is byte-identical, because those attributes change
     * privilege scope and resolution rules.
     */
    val security: ValueChange<RoutineSecurity?>? = null,
    val definer: ValueChange<String?>? = null,
    val searchPath: ValueChange<List<String>?>? = null,
    val sqlMode: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean = parameters != null || language != null || body != null ||
        sourceDialect != null || security != null || definer != null ||
        searchPath != null || sqlMode != null
}
