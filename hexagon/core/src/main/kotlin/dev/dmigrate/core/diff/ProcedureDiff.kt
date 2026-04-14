package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition

data class NamedProcedure(val name: String, val definition: ProcedureDefinition)

data class ProcedureDiff(
    val name: String,
    val parameters: ValueChange<List<ParameterDefinition>>? = null,
    val language: ValueChange<String?>? = null,
    val body: ValueChange<String?>? = null,
    val sourceDialect: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean = parameters != null || language != null || body != null ||
        sourceDialect != null
}
