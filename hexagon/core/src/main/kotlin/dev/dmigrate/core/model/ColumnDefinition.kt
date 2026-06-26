package dev.dmigrate.core.model

data class ColumnDefinition(
    val type: NeutralType,
    val required: Boolean = false,
    val unique: Boolean = false,
    val default: DefaultValue? = null,
    val references: ReferenceDefinition? = null,
    val generation: ColumnGeneration? = null,
    /**
     * Physische Spaltenposition (1-basiert) aus der Quelle. Erhält die
     * Ordinalreihenfolge über Reverse → Serialize → Generate hinweg.
     * `null` bei hand-authored Schemata ohne Positionsangabe — dann gilt
     * die Einfügereihenfolge (siehe [inOrdinalOrder]). Bewusst **nicht** Teil
     * von `schema compare` / Migration-Fingerprint (order-invariant).
     */
    val ordinal: Int? = null,
)

/**
 * Spalten in **physischer Reihenfolge**: nach [ColumnDefinition.ordinal] aufsteigend,
 * `null`-Ordinale ans Ende. Die Sortierung ist **stabil**, d. h. Spalten ohne `ordinal`
 * (hand-authored, Overlay-Zusatz) behalten ihre Einfügereihenfolge. Single Source of
 * Truth für Serialisierung und alle DDL-Generate-Pfade.
 */
fun Map<String, ColumnDefinition>.inOrdinalOrder(): List<Map.Entry<String, ColumnDefinition>> =
    entries.sortedWith(compareBy(nullsLast<Int>()) { it.value.ordinal })
