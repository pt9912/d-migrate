package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.profiling.types.TargetLogicalType

/**
 * Die SQL-Ausdruecke, mit denen der Profiler eine Oracle-Spalte anfasst.
 *
 * Vier Oracle-Eigenheiten stecken hier:
 *
 * - **LOBs sind nicht vergleichbar.** `COUNT(DISTINCT clob)` scheitert mit
 *   ORA-22849, ebenso `GROUP BY` und `ORDER BY`. Der Profiler vergleicht sie
 *   deshalb ueber die ersten [LOB_PROJECTION_CHARS] Zeichen — als
 *   `VARCHAR2`, nicht als `CLOB`: `SUBSTR` auf einem CLOB liefert wieder
 *   einen CLOB und liefe in denselben Fehler.
 * - **Ein Leerstring ist NULL.** `''` und `NULL` sind in Oracle dasselbe.
 * - **Zeit- und Zahlformate haengen an der Sitzung.** `TO_CHAR` ohne Maske
 *   richtet sich nach `NLS_DATE_FORMAT` bzw. `NLS_NUMERIC_CHARACTERS`;
 *   dasselbe Profil fiele je nach Anmeldung anders aus. Alle Masken hier
 *   stehen ausgeschrieben, und die Zahlform pinnt den Dezimalpunkt.
 * - **`LONG` und Datei-/Objektspalten lassen sich gar nicht aggregieren.**
 *   `COUNT(long)` ist ORA-00997, und es gibt keine SQL-Projektion darauf.
 *   [unprofilableReason] benennt sie, statt den Serverfehler durchzureichen.
 */
internal object OracleProfilingExpressions {

    /** Wie viele Zeichen eines LOB in den Vergleich eingehen. */
    const val LOB_PROJECTION_CHARS = 4000

    /** Wie viele Bytes eines BLOB in die Hex-Darstellung eingehen. */
    private const val BLOB_PROJECTION_BYTES = 2000

    private fun baseType(dbType: String): String = dbType.uppercase().trim().substringBefore('(').trim()

    fun isTextual(dbType: String): Boolean = baseType(dbType) in TEXTUAL

    fun isCharacterLob(dbType: String): Boolean = baseType(dbType) in CHARACTER_LOB

    fun isNumeric(dbType: String): Boolean = baseType(dbType) in NUMERIC

    /**
     * Warum sich eine Spalte dieses Typs nicht profilieren laesst — oder
     * `null`.
     *
     * `LONG`/`LONG RAW` weisen jede Verwendung in einer Aggregatfunktion ab
     * (ORA-00997) und haben keine SQL-Projektion; `BFILE` ist ein Verweis auf
     * eine Datei ausserhalb der Datenbank; ein Objekttyp ohne
     * MAP-/ORDER-Methode laesst sich nicht vergleichen.
     */
    fun unprofilableReason(dbType: String): String? = when (baseType(dbType)) {
        "LONG", "LONG RAW" -> "Oracle rejects LONG columns in aggregate functions (ORA-00997) and offers " +
            "no SQL projection for them"
        "BFILE" -> "a BFILE is a locator for a file outside the database; its content is not readable in SQL"
        in OBJECT_LIKE -> "an object type without a MAP or ORDER method cannot be compared or grouped"
        else -> null
    }

    /**
     * Der Ausdruck, ueber den gruppiert und gezaehlt wird. Fuer alles ausser
     * LOBs und XML ist das die Spalte selbst.
     */
    fun comparable(column: String, dbType: String): String = projection(column, dbType) ?: column

    /**
     * Der Ausdruck, der einen Wert als **Text** liefert — fuer `minValue`,
     * `maxValue` und die Beispielwerte.
     */
    fun display(column: String, dbType: String): String =
        projection(column, dbType) ?: when {
            isNumeric(dbType) -> numberText(column)
            baseType(dbType) in BINARY -> "RAWTOHEX($column)"
            temporalMask(dbType) != null -> "TO_CHAR($column, '${temporalMask(dbType)}')"
            else -> "TO_CHAR($column)"
        }

    /** Die verkuerzte Textform eines LOB/XML-Werts, oder null fuer alles andere. */
    private fun projection(column: String, dbType: String): String? = when (baseType(dbType)) {
        // `TO_CHAR` ist tragend: ohne es bliebe der Ausdruck ein CLOB.
        in CHARACTER_LOB -> "TO_CHAR(SUBSTR($column, 1, $LOB_PROJECTION_CHARS))"
        "BLOB" -> "RAWTOHEX(DBMS_LOB.SUBSTR($column, $BLOB_PROJECTION_BYTES, 1))"
        // `AS VARCHAR2` statt `AS CLOB` -- sonst derselbe ORA-22849.
        "XMLTYPE" -> "XMLSERIALIZE(CONTENT $column AS VARCHAR2($LOB_PROJECTION_CHARS))"
        "JSON" -> "JSON_SERIALIZE($column RETURNING VARCHAR2($LOB_PROJECTION_CHARS))"
        else -> null
    }

    /**
     * Die Zeitmaske je Typ, oder null fuer einen nicht-zeitlichen Typ.
     *
     * `DATE` traegt keine Bruchteilsekunden — `.FF` daran ist ORA-01821. Ein
     * `TIMESTAMP` traegt sie, und ohne sie faenden zwei verschiedene
     * Zeitpunkte im Profil denselben Text: `topValues` zeigte denselben Wert
     * zweimal, und die Sortierung waere bei Gleichstand unbestimmt.
     */
    fun temporalMask(dbType: String): String? {
        val base = baseType(dbType)
        val full = dbType.uppercase()
        return when {
            base == "DATE" -> ISO_SECONDS
            !base.startsWith("TIMESTAMP") -> null
            full.contains("TIME ZONE") -> "$ISO_SECONDS.FF9 TZH:TZM"
            else -> "$ISO_SECONDS.FF9"
        }
    }

    /** Die Laenge in Zeichen; LOBs zaehlen ueber `DBMS_LOB`. */
    fun length(column: String, dbType: String): String =
        if (isCharacterLob(dbType)) "DBMS_LOB.GETLENGTH($column)" else "LENGTH($column)"

    /**
     * Die Textform einer Zahl mit festem Dezimalpunkt.
     *
     * `TO_CHAR(n)` ohne Angabe folgt `NLS_NUMERIC_CHARACTERS`; in einer
     * Sitzung mit Dezimalkomma stuende `10,5` im Profil und in der naechsten
     * `10.5`. `TM9` ist die kuerzeste verlustfreie Form.
     */
    private fun numberText(column: String): String =
        "TO_CHAR($column, 'TM9', 'NLS_NUMERIC_CHARACTERS = ''$DECIMAL_POINT''')"

    /**
     * Ob ein Wert in den Zieltyp passt.
     *
     * Fuer eine **Zahlenspalte** ohne Umweg ueber Text: die Frage ist dann
     * eine ueber den Wert, nicht ueber seine Schreibweise, und ein
     * Text-Zwischenschritt machte die Antwort von der Sitzung abhaengig.
     * Fuer eine Textspalte antwortet `VALIDATE_CONVERSION` auf der Spalte
     * selbst — so, wie Oracle den Wert bei einer echten Wandlung laese.
     *
     * Die Datumsmasken stehen ausgeschrieben: ohne sie entschiede
     * `NLS_DATE_FORMAT` mit, und `13/02/2024` waere je nach Sitzung ein
     * Datum oder keins.
     */
    fun fits(column: String, dbType: String, targetType: TargetLogicalType): String = when {
        // Bei einer Zahlenspalte ist die Frage eine ueber den Wert, nicht
        // ueber seine Schreibweise; ein Text-Zwischenschritt machte die
        // Antwort von der Sitzung abhaengig.
        isNumeric(dbType) -> numericFits(column, targetType)
        // Eine Zeitspalte ist bereits ein Zeitpunkt -- es gibt nichts zu
        // pruefen. `VALIDATE_CONVERSION` naehme sie ohnehin nicht (ORA-43909).
        temporalMask(dbType) != null -> temporalFits(targetType)
        // Alles Uebrige geht ueber die **Textform**: `VALIDATE_CONVERSION`
        // nimmt weder LOB noch RAW, [display] liefert fuer beide ein
        // VARCHAR2.
        else -> textFits(display(column, dbType), targetType)
    }

    private fun numericFits(column: String, targetType: TargetLogicalType): String = when (targetType) {
        TargetLogicalType.INTEGER -> "MOD($column, 1) = 0"
        TargetLogicalType.DECIMAL, TargetLogicalType.STRING -> ALWAYS
        TargetLogicalType.BOOLEAN -> "$column IN (0, 1)"
        TargetLogicalType.DATE, TargetLogicalType.DATETIME -> NEVER
    }

    private fun temporalFits(targetType: TargetLogicalType): String = when (targetType) {
        TargetLogicalType.DATE, TargetLogicalType.DATETIME, TargetLogicalType.STRING -> ALWAYS
        TargetLogicalType.INTEGER, TargetLogicalType.DECIMAL, TargetLogicalType.BOOLEAN -> NEVER
    }

    private fun textFits(value: String, targetType: TargetLogicalType): String = when (targetType) {
        // Ein Trennzeichen -- welches auch immer -- macht aus der Zahl keine
        // Ganzzahl. Beide auszuschliessen ist sitzungsunabhaengig, anders als
        // eine Pruefung auf den Dezimalpunkt allein.
        TargetLogicalType.INTEGER ->
            "VALIDATE_CONVERSION($value AS NUMBER) = 1 AND $value NOT LIKE '%.%' AND $value NOT LIKE '%,%'"
        TargetLogicalType.DECIMAL -> "VALIDATE_CONVERSION($value AS NUMBER) = 1"
        TargetLogicalType.BOOLEAN -> "LOWER($value) IN ('0','1','true','false','yes','no')"
        TargetLogicalType.DATE -> isoFits(value, "DATE", ISO_DATE, COMPACT_DATE)
        TargetLogicalType.DATETIME -> isoFits(value, "TIMESTAMP", ISO_SECONDS, ISO_DATE)
        TargetLogicalType.STRING -> ALWAYS
    }

    private fun isoFits(column: String, target: String, vararg masks: String): String =
        masks.joinToString(" OR ") { "VALIDATE_CONVERSION($column AS $target, '$it') = 1" }.let { "($it)" }

    private const val ALWAYS = "1 = 1"
    private const val NEVER = "1 = 0"
    private const val DECIMAL_POINT = ".,"
    private const val ISO_DATE = "YYYY-MM-DD"
    private const val COMPACT_DATE = "YYYYMMDD"
    private const val ISO_SECONDS = "YYYY-MM-DD\"T\"HH24:MI:SS"

    private val TEXTUAL = setOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "CLOB", "NCLOB")
    private val CHARACTER_LOB = setOf("CLOB", "NCLOB")
    private val BINARY = setOf("RAW")
    private val NUMERIC = setOf("NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE")

    /** Objektartige Typen ohne Vergleichsordnung. */
    private val OBJECT_LIKE = setOf("SDO_GEOMETRY", "ANYDATA", "ANYTYPE")
}
