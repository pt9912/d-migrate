package dev.dmigrate.driver

/**
 * Oracle-Serverversion, so wie `product_component_version` sie ausweist.
 *
 * Der Banner nennt das Jahr („Oracle AI Database 26ai"), die Release-Nummer
 * dagegen die Feature-Linie: `23.26.3.0.0` gehoert zur 23er-Linie. Verglichen
 * wird deshalb [major], nicht der Banner.
 */
data class OracleServerVersion(
    val major: Int,
    val raw: String,
) : ServerVersion {

    /**
     * Ob der Server `DROP <objekt> IF EXISTS` kennt.
     *
     * Die Klausel kam mit der 23er-Linie; davor bricht jedes `DROP` ab, sobald
     * das Objekt fehlt. Sie gilt fuer Objekt-`DROP`s — Tabelle, View,
     * Materialized View, Index, Sequenz, Typ, Funktion, Prozedur, Trigger —,
     * **nicht** fuer `ALTER TABLE … DROP CONSTRAINT`: diese Form lehnt auch die
     * 23er-Linie mit `ORA-01735` ab.
     */
    val supportsDropIfExists: Boolean
        get() = major >= FIRST_MAJOR_WITH_DROP_IF_EXISTS

    companion object {
        private const val FIRST_MAJOR_WITH_DROP_IF_EXISTS = 23

        private val RELEASE_NUMBER = Regex("""^(\d+)(?:\.\d+)*$""")

        /**
         * Liest `23.26.3.0.0`, `19.0.0.0.0`, `23`. Gibt `null` fuer alles
         * zurueck, was nicht als punktgetrennte Release-Nummer beginnt —
         * insbesondere fuer Bannertexte.
         */
        fun parse(raw: String): OracleServerVersion? {
            val trimmed = raw.trim()
            val major = RELEASE_NUMBER.matchEntire(trimmed)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: return null
            return OracleServerVersion(major, trimmed)
        }
    }
}
