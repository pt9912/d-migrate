package dev.dmigrate.driver.data

/**
 * Writer-side Spalten-Metadaten für eine Import-Zieltabelle.
 *
 * Lebt in `hexagon:ports` (nicht in `hexagon:core`), weil [jdbcType] semantisch
 * JDBC-coupled ist. `core.ColumnDescriptor` bleibt JDBC-frei (L15).
 *
 * Die Konversion zu `formats.JdbcTypeHint` erfolgt im `StreamingImporter`,
 * der beide Module kennt.
 *
 * @property name Spaltenname
 * @property nullable Ob die Spalte NULL erlaubt
 * @property jdbcType JDBC-Typcode aus `ResultSetMetaData.getColumnType()`
 * @property sqlTypeName Dialekt-spezifischer Type-Name (sekundärer Hint
 *   für mehrdeutige jdbcType-Werte, z.B. PG `Types.OTHER`)
 * @property srid VA2 (Spatial): SRID-Constraint einer Geometrie-Zielspalte
 *   (PostGIS `geometry_columns`, MySQL `information_schema.columns.SRS_ID`).
 *   Ist sie gesetzt, bindet der Import-Pfad WKB mit `ST_GeomFromWKB(?, srid)`,
 *   damit das Ziel die SRID nicht auf 0 zurücksetzt. `null` → keine SRID.
 */
data class TargetColumn(
    val name: String,
    val nullable: Boolean,
    val jdbcType: Int,
    val sqlTypeName: String? = null,
    val srid: Int? = null,
)
