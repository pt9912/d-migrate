package dev.dmigrate.format.data

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Date as SqlDate
import java.sql.Time as SqlTime
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

/**
 * Implementiert die Java-Klasse → Format-Repräsentation Mapping-Tabelle aus
 * Plan §6.4.1.
 *
 * Konvertiert einen rohen JDBC-Wert in einen "neutralen" [SerializedValue],
 * den die drei Format-Writer (JSON/YAML/CSV) format-spezifisch encoden.
 *
 * Das Type-Routing geschieht über `value::class` zur Laufzeit — bewusst kein
 * `javaTypeName`-Hint im [dev.dmigrate.core.data.ColumnDescriptor], weil der
 * für `null`-Werte nutzlos und für die Dispatch redundant wäre (siehe §6.4).
 *
 * Unbekannte Java-Klassen fallen auf `value.toString()` zurück und erzeugen
 * eine W202-Warnung pro `(table, column, javaClass)`-Tripel im Export-Report.
 */
class ValueSerializer(
    /**
     * Sink für W202-Warnings. Wird vom Caller mit einem MutableList oder
     * einem Set-basiertem Deduplizierer befüllt. `null` für "ignorieren".
     */
    private val warningSink: ((W202) -> Unit)? = null,
) {

    /** Eine W202-Warnung für eine unbekannte Java-Klasse (§6.4.1 Fallback). */
    data class W202(
        val table: String,
        val column: String,
        val javaClass: String,
        val message: String,
    )

    /**
     * Convertiert einen rohen JDBC-Wert in einen [SerializedValue].
     *
     * @param table Tabellenname (für die W202-Warning)
     * @param column Spaltenname
     * @param value Der rohe Wert aus `ResultSet.getObject(...)` — kann null sein
     */
    fun serialize(table: String, column: String, value: Any?): SerializedValue {
        if (value == null) return SerializedValue.Null

        return when (value) {
            // String / numerisch / boolean — direkte Mapping
            is String -> SerializedValue.Text(value)
            is Boolean -> SerializedValue.Bool(value)
            is Byte -> SerializedValue.Integer(value.toLong())
            is Short -> SerializedValue.Integer(value.toLong())
            is Int -> SerializedValue.Integer(value.toLong())
            is Long -> SerializedValue.Integer(value)
            is Float -> SerializedValue.FloatingPoint(value.toDouble())
            is Double -> SerializedValue.FloatingPoint(value)

            // Präzisions-relevant: als String, niemals JSON-Number
            // (BigDecimal über Double würde Präzision verlieren)
            is BigInteger -> SerializedValue.PreciseNumber(value.toString())
            is BigDecimal -> SerializedValue.PreciseNumber(value.toPlainString())

            // Datum/Zeit — ISO 8601 String-Repräsentation
            is SqlDate -> SerializedValue.Text(value.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
            is SqlTime -> SerializedValue.Text(value.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME))
            is Timestamp -> SerializedValue.Text(value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            is LocalDate -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_LOCAL_DATE))
            is LocalTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_LOCAL_TIME))
            is LocalDateTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            is OffsetDateTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            is ZonedDateTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            is Instant -> SerializedValue.Text(value.toString())

            // UUID
            is UUID -> SerializedValue.Text(value.toString())

            // Binärdaten — Base64
            is ByteArray -> SerializedValue.Text(Base64.getEncoder().encodeToString(value))

            // Fallback: toString() + W202
            else -> {
                emitWarning(table, column, value)
                SerializedValue.Text(value.toString())
            }
        }
    }

    private fun emitWarning(table: String, column: String, value: Any) {
        val sink = warningSink ?: return
        sink(
            W202(
                table = table,
                column = column,
                javaClass = value.javaClass.name,
                message = "Unknown Java class '${value.javaClass.name}' for column " +
                    "'$table.$column' — fell back to toString()",
            )
        )
    }
}

/**
 * Format-neutrale Repräsentation eines Werts. Die drei Format-Writer
 * (JSON/YAML/CSV) interpretieren das in ihrer eigenen Output-Form:
 *
 * - **JSON**: `Null`→`null`, `Bool`→`true`/`false`, `Integer`→Number,
 *   `FloatingPoint`→Number (NaN/Inf → String mit Warnung), `PreciseNumber`→
 *   String (Präzisionsschutz!), `Text`→quoted String.
 * - **YAML**: `Null`→`~`, `Bool`→`true`/`false`, `Integer`→Number,
 *   `FloatingPoint`→Number, `PreciseNumber`→String (Präzisionsschutz),
 *   `Text`→quoted String.
 * - **CSV**: alles flach als String. `Null`→`csvNullString` (Default leer),
 *   `Bool`→`true`/`false`, `Integer`/`FloatingPoint`/`PreciseNumber`→
 *   `toString()`, `Text`→Wert (Quoting via CsvWriter wenn Sonderzeichen).
 */
sealed class SerializedValue {
    object Null : SerializedValue()
    data class Bool(val value: Boolean) : SerializedValue()
    data class Integer(val value: Long) : SerializedValue()
    data class FloatingPoint(val value: Double) : SerializedValue()
    /** Als String repräsentiert um Präzision bei BigDecimal/BigInteger zu erhalten. */
    data class PreciseNumber(val value: String) : SerializedValue()
    data class Text(val value: String) : SerializedValue()
}
