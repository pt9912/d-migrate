package dev.dmigrate.core.diff

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity

data class NamedFunction(val name: String, val definition: FunctionDefinition)

data class FunctionDiff(
    val name: String,
    val parameters: ValueChange<List<ParameterDefinition>>? = null,
    val returns: ValueChange<ReturnType?>? = null,
    val language: ValueChange<String?>? = null,
    val deterministic: ValueChange<Boolean?>? = null,
    val body: ValueChange<String?>? = null,
    val sourceDialect: ValueChange<String?>? = null,
    /**
     * E.1 Routine-Migration Slice A: changes to the routine's
     * security / definer / search-path / sql-mode attributes are
     * surfaced separately from the body change so renderers can
     * tell why a Replace is necessary even when the body hash is
     * unchanged.
     */
    val security: ValueChange<RoutineSecurity?>? = null,
    val definer: ValueChange<String?>? = null,
    val searchPath: ValueChange<List<String>?>? = null,
    val sqlMode: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean = parameters != null || returns != null || language != null ||
        deterministic != null || body != null || sourceDialect != null ||
        security != null || definer != null || searchPath != null || sqlMode != null
}
