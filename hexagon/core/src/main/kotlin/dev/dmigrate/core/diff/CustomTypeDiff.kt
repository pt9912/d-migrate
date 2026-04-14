package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind

data class NamedCustomType(val name: String, val definition: CustomTypeDefinition)

data class CustomTypeDiff(
    val name: String,
    val kind: ValueChange<CustomTypeKind>? = null,
    val values: ValueChange<List<String>>? = null,
    val baseType: ValueChange<String?>? = null,
    val precision: ValueChange<Int?>? = null,
    val scale: ValueChange<Int?>? = null,
    val check: ValueChange<String?>? = null,
    val description: ValueChange<String?>? = null,
    val fields: ValueChange<Map<String, ColumnDefinition>?>? = null,
) {
    fun hasChanges(): Boolean = kind != null || values != null || baseType != null ||
        precision != null || scale != null || check != null || description != null ||
        fields != null
}
