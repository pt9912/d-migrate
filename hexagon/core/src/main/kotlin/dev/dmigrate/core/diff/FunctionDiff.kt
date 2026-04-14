package dev.dmigrate.core.diff

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ReturnType

data class NamedFunction(val name: String, val definition: FunctionDefinition)

data class FunctionDiff(
    val name: String,
    val parameters: ValueChange<List<ParameterDefinition>>? = null,
    val returns: ValueChange<ReturnType?>? = null,
    val language: ValueChange<String?>? = null,
    val deterministic: ValueChange<Boolean?>? = null,
    val body: ValueChange<String?>? = null,
    val sourceDialect: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean = parameters != null || returns != null || language != null ||
        deterministic != null || body != null || sourceDialect != null
}
