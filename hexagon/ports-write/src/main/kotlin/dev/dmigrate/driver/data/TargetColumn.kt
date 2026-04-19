package dev.dmigrate.driver.data

/**
 * Writer-side Spalten-Metadaten für eine Import-Zieltabelle.
 *
 * Lebt in `hexagon:ports` (nicht in `hexagon:core`), weil [jdbcType] semantisch
 * JDBC-coupled ist. `core.ColumnDescriptor` bleibt JDBC-frei (L15).
 *
 * Die Konversion zu `formats.JdbcTypeHint` erfolgt im Phase-D
 * `StreamingImporter`, der beide Module kennt.
 *
 * @property name Spaltenname
 * @property nullable Ob die Spalte NULL erlaubt
 * @property jdbcType JDBC-Typcode aus `ResultSetMetaData.getColumnType()`
 * @property sqlTypeName Dialekt-spezifischer Type-Name (sekundärer Hint
 *   für mehrdeutige jdbcType-Werte, z.B. PG `Types.OTHER`)
 */
data class TargetColumn(
    val name: String,
    val nullable: Boolean,
    val jdbcType: Int,
    val sqlTypeName: String? = null,
)
