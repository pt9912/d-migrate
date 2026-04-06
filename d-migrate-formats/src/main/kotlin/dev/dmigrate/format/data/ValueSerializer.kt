package dev.dmigrate.format.data

import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Array as SqlArray
import java.sql.Blob
import java.sql.Clob
import java.sql.Date as SqlDate
import java.sql.Struct
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
 * Plan §6.4.1 vollständig.
 *
 * Konvertiert einen rohen JDBC-Wert in einen "neutralen" [SerializedValue],
 * den die drei Format-Writer (JSON/YAML/CSV) format-spezifisch encoden.
 *
 * Das Type-Routing geschieht über `value::class` zur Laufzeit (siehe §6.4).
 *
 * **Warnings**:
 * - **W201** — bekannter, aber nicht oder nur best-effort unterstützter
 *   Typ (z.B. `java.sql.Struct`, `java.sql.Array`, PostgreSQL Geometric Types)
 * - **W202** — komplett unbekannte Java-Klasse, Fallback auf `toString()`,
 *   plus NaN/Infinity-Floats die in JSON/YAML nicht als Number darstellbar sind
 *
 * Beide werden pro `(code, table, column, javaClass)`-Tupel höchstens einmal
 * an den optionalen [warningSink] geliefert. Die Deduplizierung passiert
 * intern.
 */
class ValueSerializer(
    /**
     * Sink für Warnings. Kann `null` sein, dann werden Warnings verworfen.
     * Pro `(code, table, column, javaClass)`-Tupel wird der Sink höchstens
     * einmal aufgerufen.
     */
    private val warningSink: ((Warning) -> Unit)? = null,
) {

    /** Eine Warnung aus dem Mapping-Pfad. Siehe Plan §6.4.1. */
    data class Warning(
        val code: String,
        val table: String,
        val column: String,
        val javaClass: String,
        val message: String,
    )

    /** Tracking für die Deduplizierung. Pro Tupel höchstens einmal warnen. */
    private val emittedWarnings = HashSet<String>()

    /**
     * Convertiert einen rohen JDBC-Wert in einen [SerializedValue].
     */
    fun serialize(table: String, column: String, value: Any?): SerializedValue {
        if (value == null) return SerializedValue.Null

        return when (value) {
            // ─── Primitive direkt ───────────────────────────────
            is String -> SerializedValue.Text(value)
            is Boolean -> SerializedValue.Bool(value)
            is Byte -> SerializedValue.Integer(value.toLong())
            is Short -> SerializedValue.Integer(value.toLong())
            is Int -> SerializedValue.Integer(value.toLong())
            is Long -> SerializedValue.Integer(value)
            is Float -> serializeFloating(table, column, value.toDouble())
            is Double -> serializeFloating(table, column, value)

            // ─── Präzisions-relevante Numerik ───────────────────
            is BigInteger -> SerializedValue.PreciseNumber(value.toString())
            is BigDecimal -> SerializedValue.PreciseNumber(value.toPlainString())

            // ─── Datum/Zeit als ISO 8601 ────────────────────────
            is SqlDate -> SerializedValue.Text(value.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
            is SqlTime -> SerializedValue.Text(value.toLocalTime().format(DateTimeFormatter.ISO_LOCAL_TIME))
            is Timestamp -> SerializedValue.Text(value.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            is LocalDate -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_LOCAL_DATE))
            is LocalTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_LOCAL_TIME))
            is LocalDateTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            is OffsetDateTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            is ZonedDateTime -> SerializedValue.Text(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            is Instant -> SerializedValue.Text(value.toString())

            // ─── UUID ───────────────────────────────────────────
            is UUID -> SerializedValue.Text(value.toString())

            // ─── Binärdaten als Base64 ──────────────────────────
            is ByteArray -> SerializedValue.Text(base64(value))

            // ─── JDBC LOB-Typen ─────────────────────────────────
            is Blob -> SerializedValue.Text(base64(readBlob(value)))
            is Clob -> SerializedValue.Text(readClob(value))

            // ─── JDBC Array (W201, best-effort toString) ────────
            // Für JSON/YAML rekursiv darstellbar (in 0.5.0), für CSV nicht
            // — in 0.3.0 einheitlich als toString-form mit W201.
            is SqlArray -> {
                emit("W201", table, column, value, "java.sql.Array is not first-class supported in 0.3.0; using toString()")
                SerializedValue.Text(arrayToString(value))
            }

            // ─── JDBC Struct (W201) ─────────────────────────────
            is Struct -> {
                emit("W201", table, column, value, "java.sql.Struct is not supported in 0.3.0; using toString()")
                SerializedValue.Text(value.toString())
            }

            // ─── PostgreSQL-spezifische Typen via class name ────
            // (Wir referenzieren die Klassen NICHT direkt, weil das eine
            // Compile-Time-Dependency auf den PG-Treiber wäre.)
            else -> serializeByClassName(table, column, value)
        }
    }

    private fun serializeFloating(table: String, column: String, value: Double): SerializedValue {
        return if (value.isNaN() || value.isInfinite()) {
            // §6.4.1: NaN/Infinity → JSON-String mit W202-Warnung
            emit(
                "W202", table, column, value,
                "Floating-point value '$value' is not a valid JSON number; encoded as string"
            )
            SerializedValue.Text(value.toString())
        } else {
            SerializedValue.FloatingPoint(value)
        }
    }

    private fun serializeByClassName(table: String, column: String, value: Any): SerializedValue {
        val className = value.javaClass.name
        return when {
            // PostgreSQL Generic Object — getValue() liefert die String-Repräsentation
            className == "org.postgresql.util.PGobject" -> {
                val text = invokeNoArgString(value, "getValue") ?: value.toString()
                SerializedValue.Text(text)
            }

            // PostgreSQL Geometric Types — best-effort toString() + W201
            className.startsWith("org.postgresql.geometric.") -> {
                emit("W201", table, column, value, "$className is not first-class supported in 0.3.0; using toString()")
                SerializedValue.Text(value.toString())
            }

            // Komplett unbekannt → W202-Fallback
            else -> {
                emit("W202", table, column, value, "Unknown Java class '$className' — fell back to toString()")
                SerializedValue.Text(value.toString())
            }
        }
    }

    private fun emit(code: String, table: String, column: String, value: Any, message: String) {
        val sink = warningSink ?: return
        val javaClass = value.javaClass.name
        val key = "$code|$table|$column|$javaClass"
        if (emittedWarnings.add(key)) {
            sink(Warning(code = code, table = table, column = column, javaClass = javaClass, message = message))
        }
    }

    private fun base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun readBlob(blob: Blob): ByteArray {
        val length = blob.length()
        return if (length == 0L) ByteArray(0) else blob.getBytes(1, length.toInt())
    }

    private fun readClob(clob: Clob): String {
        val length = clob.length()
        return if (length == 0L) "" else clob.getSubString(1, length.toInt())
    }

    private fun arrayToString(array: SqlArray): String {
        return try {
            val elements = array.array
            if (elements is Array<*>) {
                elements.joinToString(",", prefix = "[", postfix = "]") { it?.toString() ?: "null" }
            } else {
                elements?.toString() ?: "null"
            }
        } catch (_: Throwable) {
            array.toString()
        }
    }

    private fun invokeNoArgString(target: Any, methodName: String): String? {
        return try {
            val method = target.javaClass.getMethod(methodName)
            method.invoke(target) as? String
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Format-neutrale Repräsentation eines Werts.
 *
 * Die drei Format-Writer (JSON/YAML/CSV) interpretieren das in ihrer eigenen
 * Output-Form — siehe Plan §6.4.1 für die vollständige Mapping-Tabelle.
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
