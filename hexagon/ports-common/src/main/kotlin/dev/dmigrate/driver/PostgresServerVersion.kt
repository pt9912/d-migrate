package dev.dmigrate.driver

/**
 * Die Serverversion eines lebenden PostgreSQL, strukturell.
 *
 * Gebraucht, weil eine Faehigkeit dieses Dialekts an der Version haengt und
 * nicht am Dialekt: `ALTER COLUMN … SET EXPRESSION` gibt es **ab 17**, und die
 * zugesagte Spanne beginnt bei 14. Ohne die Version bliebe nur, die Faehigkeit
 * fuer alle zu unterstellen (falsch unterhalb von 17) oder keinem zu geben
 * (falsch ab 17).
 *
 * PostgreSQL meldet seit 10 ein `major.minor`-Paar (`18.6`, `16.4`); die
 * dreiteilige Form aus der Zeit davor liegt unterhalb der zugesagten
 * Untergrenze und wird nicht gelesen. Zusaetze wie `(Debian …)` gehoeren nicht
 * zur Version und fallen weg.
 */
data class PostgresServerVersion(
    val major: Int,
    val minor: Int,
) : ServerVersion, Comparable<PostgresServerVersion> {

    override fun compareTo(other: PostgresServerVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        return minor.compareTo(other.minor)
    }

    /** Ab wann `ALTER COLUMN … SET EXPRESSION AS (…)` zur Verfuegung steht. */
    val supportsSetExpression: Boolean
        get() = major >= SET_EXPRESSION_SINCE_MAJOR

    companion object {

        /** PostgreSQL 17 fuehrte `SET EXPRESSION` ein; live gemessen gegen 17 und 18.6. */
        const val SET_EXPRESSION_SINCE_MAJOR: Int = 17

        // Fuehrendes `major.minor`; alles danach ist Beiwerk der Distribution.
        private val VERSION_REGEX = Regex("""^(\d+)\.(\d+)""")

        /**
         * Liest `18.6`, `16.4` oder `18.6 (Debian 18.6-1.pgdg13+2)`.
         * `null`, wo kein fuehrendes Zahlenpaar steht.
         */
        fun parse(raw: String): PostgresServerVersion? {
            val match = VERSION_REGEX.find(raw.trim()) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            return PostgresServerVersion(major, minor)
        }
    }
}
