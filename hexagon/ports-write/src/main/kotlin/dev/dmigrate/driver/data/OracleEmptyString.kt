package dev.dmigrate.driver.data

/**
 * Schreib-Praeferenz fuer den leeren String gegen ein Oracle-Ziel
 * (`dialect-preference-mechanism.md`, Registry "Schreib-Mehrdeutigkeiten").
 *
 * Oracle setzt `''` mit `NULL` gleich. Ein leerer Quellwert ist damit in
 * einer `NOT NULL`-Spalte nicht schreibbar -- die Quelle ist eindeutig, das
 * Ziel kann sie nicht darstellen, und welcher Ersatz gemeint ist, weiss nur
 * der Anwender.
 *
 * **Nur `NOT NULL`-Spalten** sind betroffen. Ist die Zielspalte nullbar,
 * speichert Oracle NULL; das ist Oracles Semantik und keine Wahl, die
 * d-migrate anbietet.
 *
 * Oberflaeche: `write.oracle.empty_string` in `.d-migrate.yaml`, uebersteuert
 * durch `--oracle-empty-string`. Der Default ist konservativ -- ohne
 * Deklaration aendert d-migrate keine Daten.
 */
sealed interface OracleEmptyString {

    /** Der Lauf bricht mit benannter Meldung ab. Default. */
    data object Error : OracleEmptyString

    /** [text] tritt an die Stelle des leeren Strings. */
    data class Literal(val text: String) : OracleEmptyString

    companion object {
        /** Praefix, das einen Ersatztext von den Schluesselwoertern trennt. */
        const val LITERAL_PREFIX: String = "literal:"

        /**
         * Liest die Oberflaechen-Schreibweise.
         *
         * `null` heisst **nicht erkannt** -- der Aufrufer meldet das als
         * Konfigurationsfehler. Ein stiller Rueckfall auf den Default waere
         * hier falsch: ohne das `literal:`-Praefix waere jeder Tippfehler ein
         * gueltiger Ersatztext und landete unbemerkt in der Spalte.
         */
        fun parse(raw: String): OracleEmptyString? = when {
            raw == "error" -> Error
            raw.startsWith(LITERAL_PREFIX) -> Literal(raw.removePrefix(LITERAL_PREFIX))
            else -> null
        }
    }
}
