package dev.dmigrate.driver

/**
 * Was das Preserve-Fenster eines Dialekts **zusichert** — Probe, geschuetzte
 * Anweisungen und Restore zusammen betrachtet.
 *
 * Drei Antworten, weil es drei gibt. Zwei Booleans koennten die mittlere nicht
 * ausdruecken, ohne eine der beiden anderen zu behaupten: Oracle serialisiert
 * das Fenster zuverlaessig, kann es aber nicht zuruecknehmen — dort committet
 * **jedes** DDL implizit, und der Restore ist DDL
 * (`ALTER SEQUENCE … RESTART START WITH n`).
 *
 * Die Unterscheidung ist keine Formsache: wer [ATOMIC] liest, darf nach einem
 * Fehlschlag annehmen, dass nichts angewandt wurde. Bei [SERIALIZED] darf er
 * das nicht.
 */
enum class PreserveWindowIsolation {

    /**
     * Kein geschuetztes Fenster. Der Dialekt kann Probe und Restore nicht
     * gegen gleichzeitige Verbraucher absichern; der Preserve-Pfad steht ihm
     * nicht offen.
     */
    NONE,

    /**
     * **Niemand kommt dazwischen, aber ein Fehlschlag laesst Angewandtes
     * stehen.** Die Sperre haelt ueber das ganze Fenster, auch ueber implizite
     * Commits hinweg; eine Ruecknahme gibt es nicht.
     *
     * Oracle: die Sperre ist session-, nicht transaktionsgebunden
     * (`DBMS_LOCK.REQUEST(release_on_commit => FALSE)`) und wird ausdruecklich
     * freigegeben. Eine transaktionsgebundene faellt beim ersten DDL weg —
     * live gemessen.
     */
    SERIALIZED,

    /**
     * **Alles oder nichts.** Probe, geschuetzte Anweisungen und Restore laufen
     * in einer Transaktion auf einer Verbindung; ein Fehlschlag rollt das
     * ganze Fenster zurueck.
     */
    ATOMIC,
    ;

    /** Ob der Dialekt ueberhaupt ein geschuetztes Fenster anbietet. */
    val guardsWindow: Boolean get() = this != NONE
}
