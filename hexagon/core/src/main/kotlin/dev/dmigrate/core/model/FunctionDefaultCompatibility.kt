package dev.dmigrate.core.model

/**
 * Welcher der neutralen Funktions-Defaults auf welchem Spaltentyp zulaessig
 * ist — die einzige Quelle fuer diese Frage.
 *
 * Zwei Aufrufer mit demselben Interesse:
 *
 * - die Schema-Validierung lehnt eine unvertraegliche Kombination mit `E009` ab;
 * - ein Reverse, der einen dialektspezifischen Default auf einen neutralen
 *   Namen zurueckfuehrt (`NEWID()` → `gen_uuid`), darf genau dann
 *   kanonisieren, wenn das Ergebnis diese Pruefung besteht. Sonst tauscht er
 *   eine Ungenauigkeit gegen einen Validierungsfehler und macht das
 *   reverse-gelesene Schema unbrauchbar.
 *
 * Die Grosszuegigkeit gegenueber `text` ist Absicht: SQLite legt Zeitstempel
 * als TEXT ab (I-02).
 */
object FunctionDefaultCompatibility {

    fun isCompatible(functionName: String, type: NeutralType): Boolean = when (functionName) {
        "current_timestamp" ->
            type is NeutralType.DateTime || type is NeutralType.Date ||
                type is NeutralType.Time || type is NeutralType.Text
        // N1: CURRENT_DATE / CURRENT_TIME als Funktions-Default.
        "current_date" ->
            type is NeutralType.Date || type is NeutralType.DateTime || type is NeutralType.Text
        "current_time" ->
            type is NeutralType.Time || type is NeutralType.DateTime || type is NeutralType.Text
        "gen_uuid" -> type is NeutralType.Uuid
        else -> true
    }
}
