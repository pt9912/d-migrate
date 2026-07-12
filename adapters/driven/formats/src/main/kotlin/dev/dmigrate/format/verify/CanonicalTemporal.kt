package dev.dmigrate.format.verify

import dev.dmigrate.verify.ValueCanonicalizationException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime

/**
 * LN-009 / ADR 0030: temporale Wert-Kanonik.
 *
 * Ausgelagert aus [CanonicalValueCodec]. Datums-/Zeit-Werte werden auf ISO-8601
 * normalisiert; tz-behaftete Zeitstempel kollabieren auf einen UTC-Instant
 * (deterministisch, ohne Default-Zeitzone).
 */
internal object CanonicalTemporal {

    fun date(value: Any): String = when (value) {
        is SqlDate -> value.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        is LocalDate -> value.format(DateTimeFormatter.ISO_LOCAL_DATE)
        is LocalDateTime -> value.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        is Timestamp -> value.toLocalDateTime().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
        is String -> LocalDate.parse(value.trim().substringBefore('T').substringBefore(' ')).format(DateTimeFormatter.ISO_LOCAL_DATE)
        else -> throw cannot(value, "Date")
    }

    fun time(value: Any): String = when (value) {
        is SqlTime -> value.toLocalTime()
        is LocalTime -> value
        is LocalDateTime -> value.toLocalTime()
        is Timestamp -> value.toLocalDateTime().toLocalTime()
        is String -> LocalTime.parse(value.trim())
        else -> throw cannot(value, "Time")
    }.format(DateTimeFormatter.ISO_LOCAL_TIME)

    fun localDateTime(value: Any): String = when (value) {
        is Timestamp -> value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is LocalDateTime -> value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is SqlDate -> value.toLocalDate().atStartOfDay().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is OffsetDateTime -> value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        is String -> LocalDateTime.parse(value.trim().replace(' ', 'T')).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        else -> throw cannot(value, "DateTime")
    }

    fun instantUtc(value: Any): String =
        toInstant(value).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun toInstant(value: Any): Instant = when (value) {
        is Instant -> value
        is Timestamp -> value.toInstant()
        is OffsetDateTime -> value.toInstant()
        is ZonedDateTime -> value.toInstant()
        // Naiver Zeitstempel ohne Zone → als UTC interpretieren (deterministisch, kein Default-TZ).
        is LocalDateTime -> value.toInstant(ZoneOffset.UTC)
        is String -> runCatching { OffsetDateTime.parse(value.trim()).toInstant() }
            .getOrElse { LocalDateTime.parse(value.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
        else -> throw cannot(value, "DateTime(tz)")
    }

    private fun cannot(value: Any, expected: String): ValueCanonicalizationException =
        ValueCanonicalizationException("Wert der Klasse ${value.javaClass.name} nicht als $expected kanonisierbar")
}
