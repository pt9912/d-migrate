package dev.dmigrate.cli.commands

import dev.dmigrate.cli.i18n.TemporalFormatPolicy
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.streaming.ExportResult
import java.util.Locale

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

    /** Einzelwert-Variante für `--since-column` (selbe Regex wie `--tables`). */
    fun firstInvalidQualifiedIdentifier(value: String): String? =
        value.takeIf { !TABLE_IDENTIFIER.matches(it) }

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
    fun resolveFilter(
        rawFilter: String?,
        dialect: DatabaseDialect? = null,
        sinceColumn: String? = null,
        since: String? = null,
    ): DataFilter? {
        val rawClause = rawFilter?.takeIf { it.isNotBlank() }?.let { DataFilter.WhereClause(it) }
        val hasSince = !sinceColumn.isNullOrBlank() && !since.isNullOrBlank()
        if (!hasSince) return rawClause

        val effectiveDialect = requireNotNull(dialect) {
            "dialect is required when building a parameterized --since filter"
        }
        val markerClause = DataFilter.ParameterizedClause(
            sql = "${quoteQualifiedIdentifier(sinceColumn!!, effectiveDialect)} >= ?",
            params = listOf(parseSinceLiteral(since!!)),
        )
        return when (rawClause) {
            null -> markerClause
            else -> DataFilter.Compound(listOf(rawClause, markerClause))
        }
    }

    /** M-R5 CLI-Preflight: roher `--filter` darf in LF-013 kein `?` tragen. */
    fun containsLiteralQuestionMark(rawFilter: String?): Boolean =
        rawFilter?.contains('?') == true

    internal fun quoteQualifiedIdentifier(value: String, dialect: DatabaseDialect): String =
        value.split('.').joinToString(".") { segment ->
            when (dialect) {
                DatabaseDialect.POSTGRESQL,
                DatabaseDialect.SQLITE,
                    -> "\"${segment.replace("\"", "\"\"")}\""

                DatabaseDialect.MYSQL ->
                    "`${segment.replace("`", "``")}`"
            }
        }

    /**
     * 0.8.0 Phase E (§4.5): Delegiert an den gemeinsamen Vertrag aus
     * [TemporalFormatPolicy.parseSinceLiteral]. Das CLI-`--since`-Literal
     * bleibt konservativ typisiert:
     *
     * - Offset-haltiger ISO-String -> `OffsetDateTime`   (§4.2)
     * - lokaler ISO-DateTime       -> `LocalDateTime`    (§4.3)
     * - ISO-Datum                  -> `LocalDate`        (§4.3)
     * - Integer/Decimal/String     -> wie vor Phase E
     *
     * Es wird **keine** Default-Zeitzone gelesen: ein lokales Literal wird
     * nicht automatisch zoniert, auch nicht wenn
     * `ResolvedI18nSettings.timezone` gesetzt ist (§4.4).
     */
    internal fun parseSinceLiteral(raw: String): Any =
        TemporalFormatPolicy.parseSinceLiteral(raw)

    /**
     * Plan §3.6 / §6.10: Formatiert das [ExportResult] als ProgressSummary
     * für stderr. Zahlenformatierung läuft explizit über [Locale.US], damit
     * der Summary-String nicht vom Host-Locale abhängt.
     */
    fun formatProgressSummary(result: ExportResult): String {
        val mb = result.totalBytes.toDouble() / (1024 * 1024)
        val seconds = result.durationMs.toDouble() / 1000
        return "Exported ${result.tables.size} table(s) " +
            "(${result.totalRows} rows, ${"%.2f".format(Locale.US, mb)} MB) " +
            "in ${"%.2f".format(Locale.US, seconds)} s"
    }
}
