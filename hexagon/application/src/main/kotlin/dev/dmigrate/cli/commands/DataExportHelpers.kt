package dev.dmigrate.cli.commands

import dev.dmigrate.cli.i18n.TemporalFormatPolicy
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
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

    /** Identifier-Pattern für explizite CLI-Tabellen- und Spaltenfilter. */
    internal const val TABLE_IDENTIFIER_PATTERN =
        "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$"
    internal val TABLE_IDENTIFIER = Regex(TABLE_IDENTIFIER_PATTERN)

    /**
     * Prüft alle `--tables`-Werte gegen [TABLE_IDENTIFIER]. Liefert den
     * ersten ungültigen Wert zurück, oder `null` wenn alles passt.
     *
     * Wird auf Auto-discovered tables vom [dev.dmigrate.driver.data.TableLister]
     * NICHT angewendet — diese kommen aus dem information_schema und sind keine
     * User-Eingabe.
     */
    fun firstInvalidTableIdentifier(tables: List<String>): String? =
        tables.firstOrNull { !TABLE_IDENTIFIER.matches(it) }

    /** Einzelwert-Variante für `--since-column` (selbe Regex wie `--tables`). */
    fun firstInvalidQualifiedIdentifier(value: String): String? =
        value.takeIf { !TABLE_IDENTIFIER.matches(it) }

    /** Validiert den `--csv-delimiter`-Wert; genau ein Zeichen ist erlaubt. */
    fun parseCsvDelimiter(raw: String): Char? =
        if (raw.length == 1) raw[0] else null

    /**
     * Baut den effektiven [DataFilter] aus dem CLI-Wert.
     *
     * - `null` oder blank → `null` (kein Filter, identisch zum weggelassenen Flag)
     * - sonst → DSL parsen → [DataFilter.ParameterizedClause] mit Bind-Parametern
     *
     * `--filter` wird als geschlossene DSL geparst. Rohes SQL wird nicht
     * akzeptiert; nicht DSL-konforme Eingaben erzeugen eine
     * [FilterDslParseResult.Failure].
     *
     * @return resolved filter, or `null` if no filter is active
     * @throws FilterResolveException if the DSL parse fails
     */
    fun resolveFilter(
        parsedFilter: ParsedFilter?,
        dialect: DatabaseDialect? = null,
        sinceColumn: String? = null,
        since: String? = null,
    ): DataFilter? {
        val dslClause = parsedFilter?.let { pf ->
            val effectiveDialect = requireNotNull(dialect) {
                "dialect is required when building a parameterized filter"
            }
            FilterDslTranslator.toParameterizedClause(pf.expr, effectiveDialect)
        }
        val hasSince = !sinceColumn.isNullOrBlank() && !since.isNullOrBlank()
        if (!hasSince) return dslClause

        val effectiveDialect = requireNotNull(dialect) {
            "dialect is required when building a parameterized --since filter"
        }
        val markerClause = DataFilter.ParameterizedClause(
            sql = "${quoteQualifiedIdentifier(sinceColumn!!, effectiveDialect)} >= ?",
            params = listOf(parseSinceLiteral(since!!)),
        )
        return when (dslClause) {
            null -> markerClause
            else -> DataFilter.Compound(listOf(dslClause, markerClause))
        }
    }

    /**
     * Parses a raw `--filter` CLI string into a [ParsedFilter].
     * Returns `null` if [rawFilter] is null or blank.
     *
     * @throws FilterParseException if the DSL parse fails
     */
    fun parseFilter(rawFilter: String?): ParsedFilter? =
        dev.dmigrate.cli.commands.parseFilter(rawFilter)

    internal fun quoteQualifiedIdentifier(value: String, dialect: DatabaseDialect): String =
        SqlIdentifiers.quoteQualifiedIdentifier(value, dialect)

    /**
     * Delegiert an den gemeinsamen Zeitliteral-Vertrag. Lokale Literale
     * werden bewusst nicht automatisch mit einer Default-Zeitzone versehen.
     */
    internal fun parseSinceLiteral(raw: String): Any =
        TemporalFormatPolicy.parseSinceLiteral(raw)

    /** Formatiert die ProgressSummary mit stabiler, host-unabhängiger Locale. */
    fun formatProgressSummary(result: ExportResult): String {
        val mb = result.totalBytes.toDouble() / (1024 * 1024)
        val seconds = result.durationMs.toDouble() / 1000
        return "Exported ${result.tables.size} table(s) " +
            "(${result.totalRows} rows, ${"%.2f".format(Locale.US, mb)} MB) " +
            "in ${"%.2f".format(Locale.US, seconds)} s"
    }
}
