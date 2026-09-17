package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Die Bestaetigung, dass ein Reverse eine Auto-Increment-Spalte per
 * deklarierter Praeferenz **ohne** `legacy_serial_syntax` gelesen hat
 * (`spec/dialect-preference-mechanism.md`, Lese-Mehrdeutigkeiten).
 *
 * MySQL (`AUTO_INCREMENT` auf `bigint`) und SQLite (`AUTOINCREMENT` unter
 * der 64-Bit-Breite) tragen nicht, ob die Spalte als `SERIAL` oder als
 * IDENTITY gemeint war. Ohne Deklaration liest der Reverse `SERIAL`; wer
 * `identity` erklaert, weicht davon ab — und die Abweichung bleibt im Report
 * sichtbar. **Eine Stelle fuer beide Dialekte**, damit der Code derselbe
 * bleibt.
 */
object AutoIncrementSyntaxNote {

    /** INFO: die Spalte wurde per Praeferenz als IDENTITY gelesen. */
    const val IDENTITY_DECLARED: String = "R205"

    /**
     * @param objectName `tabelle.spalte`
     * @param construct der gelesene Ausdruck, etwa `MySQL AUTO_INCREMENT`
     * @param configKey der Konfigurationsschluessel, der die Praeferenz traegt
     */
    fun identity(objectName: String, construct: String, configKey: String): SchemaReadNote = SchemaReadNote(
        severity = SchemaReadSeverity.INFO,
        code = IDENTITY_DECLARED,
        objectName = objectName,
        message = "$construct column read as SQL-standard identity (no legacy_serial_syntax) " +
            "per declared preference ($configKey: identity)",
    )
}
