package dev.dmigrate.core.diff

data class ViewDiff(
    val name: String,
    val materialized: ValueChange<Boolean>? = null,
    val refresh: ValueChange<String?>? = null,
    val query: ValueChange<String?>? = null,
    val sourceDialect: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean =
        materialized != null || refresh != null || query != null || sourceDialect != null
}
