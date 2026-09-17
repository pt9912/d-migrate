package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.PreferenceSource
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
     * @param declaration wo die Praeferenz steht — Flag und Konfigurationsschluessel
     */
    fun identity(objectName: String, construct: String, declaration: DeclaredPreference): SchemaReadNote =
        SchemaReadNote(
            severity = SchemaReadSeverity.INFO,
            code = IDENTITY_DECLARED,
            objectName = objectName,
            message = "$construct column read as SQL-standard identity (no legacy_serial_syntax) " +
                "per declared preference (${declaration.render("identity")})",
        )
}

/**
 * Die Oberflaeche einer Lese-Praeferenz, wie der Anwender sie schreibt: ein
 * Flag und ein Konfigurationsschluessel. Eine bestaetigende Note nennt die
 * Stelle, an der die Praeferenz **tatsaechlich** erklaert wurde
 * ([PreferenceSource]) — wer das Flag gesetzt hat, sucht den Wert nicht in
 * seiner Konfigurationsdatei. Ein Hinweis, der zu einem anderen Wert raet
 * ([advise]), nennt dieselbe Stelle: ohne gesetztes Flag den
 * Konfigurationsschluessel, den jede Oberflaeche liest — auch der
 * MCP-Server, der kein Flag kennt.
 */
class DeclaredPreference(
    private val flag: String,
    private val configKey: String,
    private val source: PreferenceSource,
) {
    /** `--flag wert` bzw. `schluessel: wert`. */
    fun render(value: String): String = when (source) {
        PreferenceSource.FLAG -> "$flag $value"
        PreferenceSource.CONFIG -> "$configKey: $value"
    }

    /** Der Rat, [value] zu erklaeren — an der Stelle, die [render] nennt. */
    fun advise(value: String): String = when (source) {
        PreferenceSource.FLAG -> "pass ${render(value)}"
        PreferenceSource.CONFIG -> "declare ${render(value)} in the configuration"
    }
}
