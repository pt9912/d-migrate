package dev.dmigrate.core.data

/**
 * Spaltenmetadaten für einen [DataChunk].
 *
 * Bewusst minimal gehalten und JDBC-frei (siehe docs/archive/implementation-plan-0.3.0.md
 * §3.7 / §6.4):
 *
 * - **kein `jdbcType: Int`** — JDBC-spezifisch, gehört nicht ins neutrale
 *   Daten-Modell
 * - **kein `javaTypeName: String`** — Format-Writer dispatchen zur Laufzeit
 *   über `value::class`, nicht über einen Type-Hint im Descriptor (Hint
 *   wäre für `null`-Werte ohnehin nutzlos und für die Dispatch redundant)
 * - **`sqlTypeName` ist optional und informativ** — wird in 0.3.0 von keinem
 *   Writer ausgewertet, dient als Hook für die 0.6.0 Reverse-Mapper-Story
 *
 * **0.4.0-Anmerkung**: Der Import-Pfad braucht einen JDBC-Typcode-Hint, um
 * String-/Number-Werte aus JSON/YAML/CSV in den richtigen Java-Typ zu
 * konvertieren (§3.5.2). Dieser Hint wird **nicht** als Feld auf
 * `ColumnDescriptor` geführt — siehe L15 im 0.4.0-Plan: ein Slot hier wäre
 * semantisch JDBC-coupled und würde gegen die obige Architektur-Regel
 * verstoßen. Stattdessen lebt der Hint als
 * [dev.dmigrate.format.data.JdbcTypeHint] im `formats`-Modul, und der
 * `ValueDeserializer` bekommt eine Lookup-Closure
 * `(columnName: String) -> JdbcTypeHint?` durchgereicht. Damit bleibt
 * `core` weiterhin JDBC-frei.
 *
 * @property name Spaltenname
 * @property nullable Ob die Spalte NULL erlauben darf (aus ResultSetMetaData)
 * @property sqlTypeName Optional: opaker DB-Type-Name aus
 *   `ResultSetMetaData#getColumnTypeName()`. In 0.3.0 nur zum Mitführen,
 *   ohne semantische Auswertung. In 0.6.0 wird der Reverse-Mapper darauf
 *   einen `NeutralType` ableiten.
 */
data class ColumnDescriptor(
    val name: String,
    val nullable: Boolean,
    val sqlTypeName: String? = null,
)
