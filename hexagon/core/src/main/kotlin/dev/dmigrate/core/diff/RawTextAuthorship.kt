package dev.dmigrate.core.diff

/**
 * Beantwortet fuer ein rohes SQL-Textfeld die einzige Frage, die das Planen
 * wirklich stellt: **hat der Autor den Text seit dem letzten Anwenden
 * geaendert?**
 *
 * Der Vergleich kann sie nicht selbst beantworten. Er sieht den Dateitext auf
 * der einen und die Katalogform auf der anderen Seite, und die stimmen nie
 * ueberein — der Server druckt den Ausdruck aus seinem Parsebaum, ein
 * `--`-Kommentar ist danach spurlos weg. Aus diesem Unterschied eine Aenderung
 * zu machen hiess, dieselbe Aenderung bei jedem Lauf erneut zu planen.
 *
 * Die Antwort kommt deshalb aus der **Herkunft**: dem Autorentext, der zuletzt
 * angewandt wurde. Zwei Autorentexte lassen sich wortgleich vergleichen; der
 * Server wird dafuer nicht gebraucht.
 *
 * `null` heisst **keine Herkunft vorhanden** — erster Lauf, verlorenes
 * Dokument. Dann wird konservativ geplant: die Aenderung wird ausgefuehrt,
 * auch wenn sie vielleicht unnoetig ist. Sie zu melden statt zu planen liesse
 * eine echte Autoraenderung beim ersten Lauf liegen, und das faellt nur auf,
 * wer die Warnung liest.
 */
fun interface RawTextAuthorship {

    /**
     * @param objectType `view`, `constraint` oder `index`
     * @param objectPath der Weg zum Objekt: `[sicht]`, `[tabelle, constraint]`, `[tabelle, index]`
     * @param field eines der vier rohen SQL-Felder
     * @param keyPosition die 1-basierte Stellung eines Ausdrucks-Schluessels, sonst `null`
     * @param authoredNow der Text, wie er heute in der Schemadatei steht
     * @return `false`, wenn der Autor ihn seit dem letzten Anwenden NICHT geaendert hat;
     *   `true`, wenn doch; `null`, wenn es dazu keine Herkunft gibt
     */
    fun authorChanged(
        objectType: String,
        objectPath: List<String>,
        field: String,
        keyPosition: Int?,
        authoredNow: String?,
    ): Boolean?
}
