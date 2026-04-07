package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.streaming.ExportResult

/**
 * Reine Helfer-Funktionen für [DataExportCommand]. Ausgelagert, damit die
 * Verzweigungs- und Validierungslogik unit-testbar ist, ohne einen Clikt-
 * Command zu instanziieren.
 *
 * Alle Funktionen hier sind seiteneffektfrei und haben keine Abhängigkeiten
 * auf den Clikt-Kontext, die Connection-Factory oder die Registries.
 */
internal object DataExportHelpers {

    /**
     * F33 / Plan §6.7: Identifier-Pattern für `--tables`. Erlaubt `<name>`
     * und `<schema>.<name>`. Beide Segmente folgen den SQL-Identifier-Regeln
     * `[A-Za-z_][A-Za-z0-9_]*`. Der Plan nennt `weird name` (mit Whitespace)
     * explizit als abzulehnenden Wert.
     */
    internal const val TABLE_IDENTIFIER_PATTERN =
        "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$"
    internal val TABLE_IDENTIFIER = Regex(TABLE_IDENTIFIER_PATTERN)

    /**
     * Prüft alle `--tables`-Werte gegen [TABLE_IDENTIFIER]. Liefert den
     * ersten ungültigen Wert zurück, oder `null` wenn alles passt.
     *
     * Wird auf Auto-discovered tables vom [dev.dmigrate.driver.data.TableLister]
     * NICHT angewendet — diese kommen aus dem information_schema und sind keine
     * User-Eingabe (siehe Plan §6.7).
     */
    fun firstInvalidTableIdentifier(tables: List<String>): String? =
        tables.firstOrNull { !TABLE_IDENTIFIER.matches(it) }

    /**
     * F35 / Plan §6.17: Validiert den `--csv-delimiter`-Wert. Muss genau ein
     * Zeichen sein. Gibt den Char zurück, oder `null` bei ungültigem Wert —
     * der Caller mappt das auf Exit 2.
     */
    fun parseCsvDelimiter(raw: String): Char? =
        if (raw.length == 1) raw[0] else null

    /**
     * F32 / Plan §6.7: Baut den effektiven [DataFilter] aus dem CLI-Wert.
     *
     * - `null` oder blank → `null` (kein Filter, identisch zum weggelassenen Flag)
     * - sonst → [DataFilter.WhereClause] mit dem Roh-String (Trust-Boundary
     *   ist die lokale Shell, siehe Plan §6.7)
     */
    fun resolveFilter(rawFilter: String?): DataFilter? =
        rawFilter?.takeIf { it.isNotBlank() }?.let { DataFilter.WhereClause(it) }

    /**
     * Plan §3.6 / §6.10: Formatiert das [ExportResult] als ProgressSummary
     * für stderr. Reiner String-Aufbau; Zahlenformatierung über `"%.2f".format`
     * (locale-abhängig, aber der bestehende E2E-Test prüft nur die Präfixe).
     */
    fun formatProgressSummary(result: ExportResult): String {
        val mb = result.totalBytes.toDouble() / (1024 * 1024)
        val seconds = result.durationMs.toDouble() / 1000
        return "Exported ${result.tables.size} table(s) " +
            "(${result.totalRows} rows, ${"%.2f".format(mb)} MB) " +
            "in ${"%.2f".format(seconds)} s"
    }
}
