package dev.dmigrate.driver.migration

/**
 * Ein Objekt, das der Server nach dem Anwenden fuehrt, aber nicht uebersetzen
 * konnte.
 *
 * Der Fall entsteht, wo eine Datenbank das `CREATE` annimmt und den Rumpf erst
 * danach uebersetzt: die Anweisung meldet Erfolg, das Objekt steht im Katalog,
 * und benutzbar ist es nicht. Ohne diese Nachfrage gaelte ein solcher Lauf als
 * gelungen.
 *
 * [line] und [position] zeigen in den **Rumpf, wie der Server ihn fuehrt** —
 * nicht in die Schemadatei des Anwenders. Sie helfen trotzdem, weil der Rumpf
 * unveraendert uebernommen wird.
 */
data class UncompiledObject(
    val name: String,
    val objectType: String,
    val line: Int,
    val position: Int,
    val message: String,
) {

    /** Eine Zeile fuer den Anwender, ohne Rumpftext. */
    fun describe(): String = "$objectType $name (Zeile $line, Spalte $position): $message"
}
