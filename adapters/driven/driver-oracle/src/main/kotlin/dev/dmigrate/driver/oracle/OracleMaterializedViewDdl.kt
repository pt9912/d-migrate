package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.model.ViewRefreshSetting

/**
 * `CREATE MATERIALIZED VIEW` und das Urteil, ob sich eine Materialized View
 * in Oracle anlegen laesst.
 *
 * Geteilt von Generate- und Diff-Pfad, damit beide dieselbe Frage gleich
 * beantworten.
 *
 * Die Refresh-Angabe des neutralen Modells nennt Methode und Ausloeser, etwa
 * `complete on demand`. Auch eins von beiden allein ist zulaessig: `complete`
 * laesst den Ausloeser auf `on demand`, `on commit` die Methode auf der
 * Voreinstellung. Fehlt die Angabe ganz, greift Oracles Voreinstellung
 * `FORCE ON DEMAND`; d-migrate schreibt sie dann auch nicht aus, um keinen
 * Unterschied zu behaupten, wo keiner ist.
 */
internal object OracleMaterializedViewDdl {

    /** Was Oracle nicht anlegen kann, mit dem Grund fuer die Meldung. */
    data class Unrenderable(val reason: String, val hint: String)

    /**
     * Der Grund, warum eine Materialized View nicht darstellbar ist — oder
     * null.
     *
     * `REFRESH FAST` verlangt ein **Materialized View Log** auf jeder
     * Basistabelle; ohne es lehnt Oracle das Anlegen mit ORA-23413 ab. Das
     * neutrale Modell fuehrt kein Log, und seine Form entscheidet mit, ob ein
     * schneller Refresh fuer die konkrete Abfrage ueberhaupt moeglich ist —
     * es zu erraten hiesse, eine Zusicherung zu erfinden.
     *
     * `FORCE` braucht dagegen keins: es faellt auf einen vollstaendigen
     * Refresh zurueck, wenn ein schneller nicht geht (live gemessen, auch mit
     * `ON COMMIT`).
     */
    fun unsupportedShape(name: String, view: ViewDefinition): Unrenderable? {
        val refresh = ViewRefreshSetting.parse(view.refresh) ?: return unparsableRefresh(name, view.refresh)
        if (refresh.method == ViewRefreshSetting.Method.FAST) {
            return Unrenderable(
                "Materialized view '$name' requests REFRESH FAST, which Oracle only allows with a " +
                    "materialized view log on every base table (ORA-23413); the neutral model carries no log.",
                "Create the materialized view log manually, or use `force` — it falls back to a complete " +
                    "refresh when a fast one is not possible.",
            )
        }
        return null
    }

    private fun unparsableRefresh(name: String, refresh: String?): Unrenderable = Unrenderable(
        "Materialized view '$name' carries the refresh setting '$refresh'. Expected a method " +
            "('complete', 'force', 'fast', 'never'), a trigger ('on demand', 'on commit'), or both.",
        "Use one of the documented refresh settings, or omit it to take the dialect default.",
    )

    /** Setzt [unsupportedShape] `== null` voraus. */
    fun createSql(
        name: String,
        view: ViewDefinition,
        query: String,
        quote: (String) -> String,
    ): String = buildString {
        append("CREATE MATERIALIZED VIEW ${quote(name)}")
        // Ohne Angabe gilt Oracles Voreinstellung FORCE ON DEMAND; sie
        // auszuschreiben behauptete einen Unterschied, wo keiner ist. Die
        // kanonische Form kennt die Voreinstellung als `null`.
        view.refresh?.let { append("\n${refreshSql(it)}") }
        append("\nAS\n$query;")
    }

    /**
     * `NEVER REFRESH` steht **vor** dem Wort `REFRESH` -- `REFRESH NEVER` ist
     * kein gueltiges Oracle (ORA-00905, gemessen).
     */
    private fun refreshSql(refresh: String): String {
        val parsed = checkNotNull(ViewRefreshSetting.parse(refresh))
        return if (parsed.method == ViewRefreshSetting.Method.NEVER) {
            "NEVER REFRESH"
        } else {
            "REFRESH ${parsed.method.name} ON ${parsed.trigger.name}"
        }
    }

    fun dropSql(name: String, quote: (String) -> String): String = "DROP MATERIALIZED VIEW ${quote(name)};"

    /**
     * Die Refresh-Angabe des Katalogs in kanonischer Schreibweise, oder
     * `null` fuer die Voreinstellung.
     *
     * `ALL_MVIEWS` fuehrt Methode und Ausloeser in zwei Spalten. Kennt das
     * Modell einen der beiden Werte nicht — Oracle kennt etwa
     * `ON STATEMENT` —, liefert die Funktion den Rohtext zurueck, statt ihn
     * still zur Voreinstellung zu machen: der Aufrufer meldet ihn dann.
     */
    fun readRefresh(method: String?, mode: String?): String? {
        if (method == null || mode == null) return null
        val raw = "${method.trim().lowercase()} on ${mode.trim().lowercase()}"
        return ViewRefreshSetting.canonical(raw)
    }

    /** Ob [readRefresh] den Katalogwert verstanden hat. */
    fun isReadable(method: String?, mode: String?): Boolean =
        method == null || mode == null ||
            ViewRefreshSetting.parse("${method.trim().lowercase()} on ${mode.trim().lowercase()}") != null

}
