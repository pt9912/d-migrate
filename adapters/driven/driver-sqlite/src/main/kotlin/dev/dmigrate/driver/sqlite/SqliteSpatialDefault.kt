package dev.dmigrate.driver.sqlite

/**
 * SpatiaLites Fuellwert an einer registrierten Geometriespalte ist **kein**
 * Anwender-Default.
 *
 * `AddGeometryColumn(…, not_null = 1)` legt die Spalte als
 * `"<spalte>" <TYP> NOT NULL DEFAULT ''` an (gemessen an SpatiaLite 5.1.0);
 * `PRAGMA table_info` meldet dafuer `dflt_value = ''`. Der SQLite-Reverse
 * liest jeden Default woertlich — er kaeme als `default: ""` ins Modell, und
 * ein Default ist selbst ein `E052`-Ausloeser. Ohne diese Ausnahme blockierte
 * der **zweite** `schema generate` genau die Tabelle, die der erste angelegt
 * hat.
 *
 * Verworfen wird der Wert nur an einer Spalte, die in `geometry_columns`
 * steht: dort **kann** er nur von SpatiaLite stammen. An jeder anderen Spalte
 * bleibt `DEFAULT ''` ein Anwender-Default und kommt unveraendert mit.
 */
internal object SqliteSpatialDefault {

    /** Das leere Zeichenkettenliteral, wie `PRAGMA table_info` es meldet. */
    private const val EMPTY_LITERAL = "''"

    /**
     * Der Default, der ins Modell gehoert — oder `null`, wenn es SpatiaLites
     * Fuellwert ist.
     */
    fun userDefault(rawDefault: String?, isRegisteredGeometry: Boolean): String? = when {
        !isRegisteredGeometry -> rawDefault
        rawDefault?.trim() == EMPTY_LITERAL -> null
        else -> rawDefault
    }
}
