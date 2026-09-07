package dev.dmigrate.core.model

/**
 * Die Refresh-Einstellung einer materialisierten Sicht in **einer**
 * Schreibweise.
 *
 * [ViewDefinition.refresh] ist ein Text, und derselbe Sachverhalt laesst sich
 * verschieden schreiben: `on_demand`, `on demand` und `force on demand` sagen
 * dasselbe. Traegt das Modell die Schreibweise des Autors, vergleicht der
 * Planer zwei gleichbedeutende Angaben als verschieden — und plant bei jedem
 * Lauf erneut ein Ersetzen der Sicht, das ihren materialisierten Bestand
 * verwirft.
 *
 * Deshalb wird der Text an der Modellgrenze auf eine kanonische Form
 * gebracht: der Parser legt ab, was [canonical] liefert, und ein Reverse-Read
 * liefert dieselbe Form. Was gleichbedeutend ist, ist danach auch gleich.
 *
 * Die Voreinstellung (`force on demand`) kanonisiert zu `null`: sie
 * auszuschreiben behauptete einen Unterschied zu einer Sicht, die nichts
 * angibt, obwohl beide dasselbe bedeuten.
 */
data class ViewRefreshSetting(val method: Method, val trigger: Trigger) {

    /** Wie aufgefrischt wird. */
    enum class Method { COMPLETE, FAST, FORCE, NEVER }

    /** Wann aufgefrischt wird. */
    enum class Trigger { DEMAND, COMMIT, NEVER }

    /** Die kanonische Schreibweise, etwa `complete on demand`. */
    override fun toString(): String =
        if (method == Method.NEVER || trigger == Trigger.NEVER) {
            NEVER_TEXT
        } else {
            "${method.name.lowercase()} on ${trigger.name.lowercase()}"
        }

    companion object {
        /** Was gilt, wenn nichts angegeben ist. */
        val DEFAULT = ViewRefreshSetting(Method.FORCE, Trigger.DEMAND)

        /** `never` steht fuer sich: ein Ausloeser dazu ergaebe keinen Sinn. */
        const val NEVER_TEXT = "never"

        /**
         * Die Angabe hinter [text], oder `null`, wenn sie sich nicht lesen
         * laesst.
         *
         * Zulaessig sind die Methode allein (`complete`), der Ausloeser allein
         * (`on commit` — der Autor sagt *wann*, nicht *wie*) und beides
         * (`complete on demand`). Unterstriche gelten als Leerzeichen, weil
         * `on_demand` eine verbreitete Schreibweise in Schemadateien ist.
         */
        fun parse(text: String?): ViewRefreshSetting? {
            if (text == null) return DEFAULT
            val parts = text.lowercase().trim().replace('_', ' ').split(Regex("\\s+"))
            return when {
                parts.size == 2 && parts[0] == "on" -> triggerOf(parts[1])?.let { setting(DEFAULT.method, it) }
                parts.size == 1 -> methodOf(parts[0])?.let { setting(it, Trigger.DEMAND) }
                parts.size == 3 && parts[1] == "on" ->
                    methodOf(parts[0])?.let { m -> triggerOf(parts[2])?.let { setting(m, it) } }
                else -> null
            }
        }

        /**
         * [text] in kanonischer Schreibweise — `null` fuer eine fehlende oder
         * die voreingestellte Angabe.
         *
         * Ein **unlesbarer** Text bleibt unveraendert stehen, statt still zu
         * verschwinden: der Aufrufer soll ihn melden koennen, und ein
         * verworfener Wert waere von einer fehlenden Angabe nicht mehr zu
         * unterscheiden.
         */
        fun canonical(text: String?): String? {
            if (text == null) return null
            val parsed = parse(text) ?: return text
            return if (parsed == DEFAULT) null else parsed.toString()
        }

        /** `never` bindet Methode und Ausloeser aneinander. */
        private fun setting(method: Method, trigger: Trigger): ViewRefreshSetting =
            if (method == Method.NEVER || trigger == Trigger.NEVER) {
                ViewRefreshSetting(Method.NEVER, Trigger.NEVER)
            } else {
                ViewRefreshSetting(method, trigger)
            }

        private fun methodOf(token: String): Method? =
            Method.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }

        private fun triggerOf(token: String): Trigger? =
            Trigger.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }
    }
}
