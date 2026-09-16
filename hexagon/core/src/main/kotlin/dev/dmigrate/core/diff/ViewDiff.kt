package dev.dmigrate.core.diff

/**
 * Der Unterschied zweier Sichten.
 *
 * [columns] traegt die **Namen** der sichtbaren Spalten, nicht ihre Typen: der
 * Typ ist Dialekt-Schreibweise (`text` gegen `nvarchar` gegen
 * `character varying`), und `ViewDefinition.columns` ist laut
 * `spec/schema.json` eine *optionale* Signatur, die die Reader unterschiedlich
 * gut fuellen. Verglichen wird deshalb nur, was **beide** Seiten tragen —
 * dieselbe Regel wie bei `TableMetadata.engine`.
 */
data class ViewDiff(
    val name: String,
    val materialized: ValueChange<Boolean>? = null,
    val refresh: ValueChange<String?>? = null,
    val query: ValueChange<String?>? = null,
    val columns: ValueChange<List<String>>? = null,
    val sourceDialect: ValueChange<String?>? = null,
) {
    fun hasChanges(): Boolean =
        materialized != null || refresh != null || query != null || columns != null || sourceDialect != null
}
