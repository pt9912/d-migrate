package dev.dmigrate.cli.i18n

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 0.8.0 Phase E — temporaler Format- und Zonen-Vertrag.
 *
 * Referenz: `docs/ImpPlan-0.8.0-E.md`. Dieser Typ ist der benannte Einstieg
 * in den Phase-E-Vertrag; er hat **keinen globalen Zustand** und liefert nur
 * Konstanten und reine Funktionen.
 *
 * Verbindliche Regeln (siehe Plan §4):
 *
 * - **§4.1 Nur kanonische erweiterte ISO-8601-Profile.**
 *   Der Vertrag bindet sich auf die JDK-Formatter
 *   `ISO_LOCAL_DATE`, `ISO_LOCAL_TIME`, `ISO_LOCAL_DATE_TIME` und
 *   `ISO_OFFSET_DATE_TIME` (erweiterte Form mit Bindestrichen/Doppelpunkten).
 *   Der Schreibpfad ist kanonisch mit Sekunden-Praezision; der Lesepfad
 *   folgt exakt dem JDK-ISO-Profil und akzeptiert damit auch die dort legal
 *   definierten reduzierten Zeitformen (z.B. `2026-01-01T10:15` ohne
 *   Sekunden-Anteil). Basic-Form ohne Trenner, Wochen- oder Ordinal-Daten,
 *   Daten ohne Tag und dialekt-lokale Varianten sind in 0.8.0 **nicht**
 *   unterstuetzt; solche Eingaben werden von [parseSinceLiteral] als
 *   Rohstring zurueckgegeben und vom `ValueDeserializer` als Typfehler
 *   abgelehnt. Strukturierte Formate (JSON/YAML/CSV) nutzen fuer dieselben
 *   temporalen Werte dieselbe ISO-Darstellung. Locale steuert hier nichts.
 * - **§4.2 Offsethaltige Werte bleiben offsethaltig.**
 *   `OffsetDateTime` und `ZonedDateTime` werden offsetbasiert serialisiert;
 *   der Offset darf nicht still verloren gehen.
 * - **§4.3 Lokale Date-Times bleiben lokal.**
 *   `LocalDateTime`/`LocalDate`/`LocalTime` tragen absichtlich keinen Offset.
 *   Kein Parser darf einen String mit Offset in `LocalDateTime` umdeuten.
 * - **§4.4 Default-Zeitzone ist nur ein expliziter Konvertierungsbaustein.**
 *   Die in Phase B aufgeloeste `ResolvedI18nSettings.timezone` darf nur dort
 *   gelesen werden, wo ein lokaler Input bewusst in einen zonierten Kontext
 *   ueberfuehrt werden soll (siehe [toZoned]).
 * - **§4.5 Ein Regelwerk fuer Export, Import und `--since`.**
 *   `ValueSerializer`/`ValueDeserializer` und
 *   `DataExportHelpers.parseSinceLiteral(...)` nutzen denselben begrifflichen
 *   Vertrag.
 * - **§4.6 Default-Zeitzone folgt der Phase-B-Resolve-Kette.**
 *   Die aufgeloeste `ResolvedI18nSettings.timezone` kommt aus
 *   `i18n.default_timezone`, sonst `ZoneId.systemDefault()`, und faellt **nur**
 *   bei Leer-/Fehlerfall auf UTC zurueck. Die Zone greift nur in der
 *   expliziten Konvertierung ueber [toZoned] und aendert die Semantik
 *   vorhandener `LocalDateTime`-Werte nicht.
 *
 * Keine locale-abhaengigen Datumsformatter im Datenpfad. Menschen-lesbare
 * CLI-Meldungen formatieren ggf. mit `Locale`, aber das passiert ausserhalb
 * dieses Policy-Typs.
 */
object TemporalFormatPolicy {

    // ────────────────────────────────────────────────────────────────
    // §4.1 — ISO 8601 Formatter (zentralisiert, damit Call-Sites sich
    // gegen denselben benannten Vertrag binden statt verstreut auf
    // java.time.format.DateTimeFormatter.ISO_* zuzugreifen)
    // ────────────────────────────────────────────────────────────────

    /** §4.3: lokales Datum ohne Offset. */
    val ISO_LOCAL_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** §4.3: lokale Uhrzeit ohne Offset. */
    val ISO_LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_TIME

    /** §4.3: lokaler Zeitstempel ohne Offset. */
    val ISO_LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /** §4.2: offsethaltiger Zeitstempel. */
    val ISO_OFFSET_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    // ────────────────────────────────────────────────────────────────
    // §4.1 / §4.2 / §4.3 — Rendering (Serialize-Seite)
    // ────────────────────────────────────────────────────────────────

    fun formatLocalDate(value: LocalDate): String = value.format(ISO_LOCAL_DATE)

    fun formatLocalTime(value: LocalTime): String = value.format(ISO_LOCAL_TIME)

    fun formatLocalDateTime(value: LocalDateTime): String = value.format(ISO_LOCAL_DATE_TIME)

    fun formatOffsetDateTime(value: OffsetDateTime): String = value.format(ISO_OFFSET_DATE_TIME)

    /**
     * §4.2: `ZonedDateTime` im strukturierten Datenpfad offsetbasiert
     * serialisieren. Die `ZoneId` ist in 0.8.0 bewusst **nicht Teil** des
     * garantierten Vertrags (siehe Plan §8 R3). Region-Transport ist ein
     * moeglicher spaeterer Ausbau.
     */
    fun formatZonedDateTime(value: ZonedDateTime): String = value.format(ISO_OFFSET_DATE_TIME)

    // ────────────────────────────────────────────────────────────────
    // §4.3 / §4.4 — Heuristik
    // ────────────────────────────────────────────────────────────────

    /**
     * §4.3: Traegt ein String-Literal explizit einen Offset oder `Z`?
     *
     * Wird genutzt, um eine stille Umdeutung `"...+02:00"` -> `LocalDateTime`
     * zu verhindern. Die Heuristik prueft nur das Tail nach dem `T`-Zeichen
     * und deckt die ISO-Standardformen ab.
     */
    fun hasOffsetOrZone(literal: String): Boolean {
        val tIdx = literal.indexOf('T')
        if (tIdx < 0) return false
        val tail = literal.substring(tIdx + 1)
        if (tail.endsWith('Z')) return true
        // Offset-Form: `+hh:mm`, `-hh:mm` oder `±hhmm` — nach dem Time-Anteil
        // ist ein `+`/`-` im Tail nur noch als Offset-Vorzeichen zulaessig.
        if (tail.any { it == '+' }) return true
        // `-` im Tail kann nicht mehr Teil von `YYYY-MM-DD` sein, weil das
        // schon vor `T` liegt. Nur Offset-Vorzeichen kommt in Frage.
        // Kurze Tails ohne explizites Offset-Vorzeichen ignorieren wir.
        return tail.indexOf('-') >= 0 && tail.length > 6
    }

    // ────────────────────────────────────────────────────────────────
    // §4.5 — gemeinsamer Parser fuer Since-Literale
    // ────────────────────────────────────────────────────────────────

    /**
     * §4.5: Parser fuer CLI-Literal `--since` und semantisch verwandte
     * Callsites. Reihenfolge und Ergebnis-Typen sind Teil des Vertrags:
     *
     * 1. Offset-haltiger ISO-String -> [OffsetDateTime]   (§4.2)
     * 2. Lokaler ISO-DateTime       -> [LocalDateTime]    (§4.3)
     * 3. ISO-Datum                  -> [LocalDate]        (§4.3)
     * 4. Ganzzahl                   -> [Long] / [BigDecimal]
     * 5. Dezimal                    -> [BigDecimal]
     * 6. Sonst                      -> Rohstring
     *
     * Es wird **keine** Default-Zeitzone gelesen. Ein lokaler
     * DateTime-Input bleibt [LocalDateTime]; fuer eine zonierte
     * Bindung muss die aufrufende Stelle [toZoned] mit einer
     * explizit uebergebenen [ZoneId] benutzen.
     */
    fun parseSinceLiteral(raw: String): Any {
        parseOffsetDateTime(raw)?.let { return it }
        parseLocalDateTime(raw)?.let { return it }
        parseLocalDate(raw)?.let { return it }
        if (INTEGER_PATTERN.matches(raw)) {
            return raw.toLongOrNull() ?: BigDecimal(raw)
        }
        if (DECIMAL_PATTERN.matches(raw)) {
            return BigDecimal(raw)
        }
        return raw
    }

    /**
     * §4.3: Offsethaltigen ISO-String parsen. Ein lokaler Input (ohne Offset)
     * liefert `null`, damit der Caller nicht still zu UTC/JVM-Local zonieren
     * kann.
     */
    fun parseOffsetDateTime(raw: String): OffsetDateTime? =
        runCatching { OffsetDateTime.parse(raw) }.getOrNull()

    /**
     * §4.3: Lokalen ISO-DateTime parsen. Ein Input mit Offset/Zone liefert
     * hier bewusst `null` statt stiller Abschneidung.
     */
    fun parseLocalDateTime(raw: String): LocalDateTime? =
        raw.takeIf { 'T' in it && !hasOffsetOrZone(it) }
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    /** §4.3: Lokales ISO-Datum ohne T-Anteil parsen. */
    fun parseLocalDate(raw: String): LocalDate? =
        raw.takeUnless { 'T' in it }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    // ────────────────────────────────────────────────────────────────
    // §4.4 — explizite Konvertierung mit Default-Zeitzone
    // ────────────────────────────────────────────────────────────────

    /**
     * §4.4: Die **einzige** Policy-API, die einen lokalen Wert in einen
     * zonierten Kontext ueberfuehrt.
     *
     * Die Zone wird vom Caller explizit uebergeben (typischerweise
     * `ResolvedI18nSettings.timezone`). Es wird **kein** JVM-Default und
     * **keine** versteckte Auflosung aus Singletons gelesen. Wer die Default-
     * Zeitzone anwenden will, muss sie an dieser API sichtbar uebergeben.
     *
     * @param local naiver lokaler Zeitwert
     * @param zone explizit aufgeloeste Ziel-Zone
     */
    fun toZoned(local: LocalDateTime, zone: ZoneId): ZonedDateTime =
        local.atZone(zone)

    // ────────────────────────────────────────────────────────────────
    // interne Muster — identisch zum Vorgaengerverhalten in
    // DataExportHelpers.parseSinceLiteral
    // ────────────────────────────────────────────────────────────────

    private val INTEGER_PATTERN = Regex("^[+-]?\\d+$")
    private val DECIMAL_PATTERN =
        Regex("^[+-]?(?:\\d+\\.\\d+|\\d+\\.\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?$|^[+-]?\\d+[eE][+-]?\\d+$")
}
