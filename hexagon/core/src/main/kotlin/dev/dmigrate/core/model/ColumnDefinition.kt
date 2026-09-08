package dev.dmigrate.core.model

data class ColumnDefinition(
    val type: NeutralType,
    val required: Boolean = false,
    val unique: Boolean = false,
    /**
     * Der Name des einspaltigen UNIQUE-Constraints, wo die Quelle ihn fuehrt.
     *
     * `unique` sagt, **dass** die Spalte eindeutig ist; dieses Feld sagt,
     * **wie der Constraint heisst**, der das durchsetzt. Beides gehoert
     * zusammen: ein `DROP CONSTRAINT` braucht den Namen, und kein Dialekt
     * ausser Oracle kennt eine Form, die einen Constraint ueber seine Spalte
     * statt ueber seinen Namen abbaut.
     *
     * `null`, wo es keinen gibt oder keiner bekannt ist — ein handgeschriebenes
     * Schema mit `unique: true`, und jeder Dialekt, dessen Reverse den Namen
     * nicht zurueckgeben kann. Ob der Name zur **Identitaet** gehoert,
     * entscheidet nicht dieses Feld, sondern die Faehigkeit des Zieldialekts.
     */
    val uniqueConstraintName: String? = null,
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
