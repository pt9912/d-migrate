package dev.dmigrate.format.parquet

import dev.dmigrate.core.model.NeutralType
import org.apache.parquet.example.data.Group
import org.apache.parquet.io.api.Binary
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Schreibt einen einzelnen Spaltenwert in eine Parquet-[Group]
 * gemaess der AP2 §8 Mapping-Tabelle. Null-Werte werden uebersprungen
 * (Parquet-OPTIONAL-Felder bleiben in dem Fall unbelegt).
 *
 * Per AP2-Mapping reisen Werte als JDBC-getypte Objekte herein
 * (`Int`/`Long`/`String`/`Boolean`/`BigDecimal`/`LocalDate`/...).
 * Inkompatible Wertetypen werfen `IllegalArgumentException` mit
 * NeutralType-Begruendung; AP3 erweitert die Mapping-Tabelle,
 * wenn ein Roundtrip-Test einen Typ unvollstaendig abbildet
 * (AP2 §10).
 */
internal object ParquetGroupValueWriter {

    fun writeColumn(group: Group, columnName: String, neutralType: NeutralType, value: Any?) {
        if (value == null) return
        when (neutralType) {
            is NeutralType.BooleanType -> group.append(columnName, asBoolean(value))
            is NeutralType.SmallInt, is NeutralType.Integer, is NeutralType.Identifier ->
                group.append(columnName, asInt(value))
            is NeutralType.BigInteger -> group.append(columnName, asLong(value))
            is NeutralType.Float -> when (neutralType.floatPrecision) {
                dev.dmigrate.core.model.FloatPrecision.SINGLE -> group.append(columnName, asFloat(value))
                dev.dmigrate.core.model.FloatPrecision.DOUBLE -> group.append(columnName, asDouble(value))
            }
            is NeutralType.Decimal ->
                writeDecimal(group, columnName, neutralType.precision, neutralType.scale, value)
            is NeutralType.Text, is NeutralType.Email, is NeutralType.Char,
            is NeutralType.Xml, is NeutralType.FullText ->
                group.append(columnName, value.toString())
            is NeutralType.Binary -> group.append(columnName, Binary.fromConstantByteArray(asByteArray(value)))
            is NeutralType.Date -> group.append(columnName, asEpochDays(value))
            is NeutralType.Time -> group.append(columnName, asMicrosOfDay(value))
            is NeutralType.DateTime ->
                group.append(columnName, asEpochMicros(value, neutralType.timezone))
            is NeutralType.Uuid ->
                group.append(columnName, Binary.fromConstantByteArray(uuidBytes(value)))
            is NeutralType.Json ->
                group.append(columnName, Binary.fromString(value.toString()))
            is NeutralType.Enum ->
                group.append(columnName, Binary.fromString(value.toString()))
            // Geometry/Array werden als BINARY/STRING gemappt — Werte
            // entsprechend serialisiert (WKB-Bytes bzw. Array-Stringform).
            is NeutralType.Geometry ->
                group.append(columnName, Binary.fromConstantByteArray(asByteArray(value)))
            is NeutralType.Array ->
                group.append(columnName, value.toString())
        }
    }

    private fun asBoolean(value: Any): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.toBooleanStrict()
        else -> error("Cannot coerce ${value::class.simpleName} to Boolean")
    }

    private fun asInt(value: Any): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Short -> value.toInt()
        is Byte -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toInt()
        else -> error("Cannot coerce ${value::class.simpleName} to Int")
    }

    private fun asLong(value: Any): Long = when (value) {
        is Long -> value
        is Number -> value.toLong()
        is String -> value.toLong()
        else -> error("Cannot coerce ${value::class.simpleName} to Long")
    }

    private fun asFloat(value: Any): Float = when (value) {
        is Float -> value
        is Number -> value.toFloat()
        is String -> value.toFloat()
        else -> error("Cannot coerce ${value::class.simpleName} to Float")
    }

    private fun asDouble(value: Any): Double = when (value) {
        is Double -> value
        is Number -> value.toDouble()
        is String -> value.toDouble()
        else -> error("Cannot coerce ${value::class.simpleName} to Double")
    }

    private fun writeDecimal(group: Group, columnName: String, precision: Int, scale: Int, value: Any) {
        val decimal = when (value) {
            is BigDecimal -> value.setScale(scale)
            is Number -> BigDecimal(value.toString()).setScale(scale)
            is String -> BigDecimal(value).setScale(scale)
            else -> error("Cannot coerce ${value::class.simpleName} to BigDecimal")
        }
        val unscaled: BigInteger = decimal.unscaledValue()
        when {
            precision <= 9 -> group.append(columnName, unscaled.toInt())
            precision <= 18 -> group.append(columnName, unscaled.toLong())
            else -> group.append(columnName, Binary.fromConstantByteArray(unscaled.toByteArray()))
        }
    }

    private fun asByteArray(value: Any): ByteArray = when (value) {
        is ByteArray -> value
        is ByteBuffer -> ByteArray(value.remaining()).also { value.duplicate().get(it) }
        is String -> value.toByteArray()
        else -> error("Cannot coerce ${value::class.simpleName} to ByteArray")
    }

    private fun asEpochDays(value: Any): Int = when (value) {
        is LocalDate -> value.toEpochDay().toInt()
        is java.sql.Date -> value.toLocalDate().toEpochDay().toInt()
        is String -> LocalDate.parse(value).toEpochDay().toInt()
        else -> error("Cannot coerce ${value::class.simpleName} to LocalDate")
    }

    private fun asMicrosOfDay(value: Any): Int = when (value) {
        is LocalTime -> (value.toNanoOfDay() / NANOS_PER_MICRO).toInt()
        is java.sql.Time -> (value.toLocalTime().toNanoOfDay() / NANOS_PER_MICRO).toInt()
        is String -> (LocalTime.parse(value).toNanoOfDay() / NANOS_PER_MICRO).toInt()
        else -> error("Cannot coerce ${value::class.simpleName} to LocalTime")
    }

    private fun asEpochMicros(value: Any, withTimezone: Boolean): Long = when (value) {
        is Instant -> instantToMicros(value)
        is OffsetDateTime -> instantToMicros(value.toInstant())
        is LocalDateTime -> instantToMicros(value.toInstant(ZoneOffset.UTC))
        is java.sql.Timestamp -> instantToMicros(value.toInstant())
        is String -> if (withTimezone) {
            instantToMicros(OffsetDateTime.parse(value).toInstant())
        } else {
            instantToMicros(LocalDateTime.parse(value).toInstant(ZoneOffset.UTC))
        }
        else -> error("Cannot coerce ${value::class.simpleName} to DateTime micros")
    }

    private fun instantToMicros(instant: Instant): Long =
        instant.epochSecond * MICROS_PER_SECOND + instant.nano / NANOS_PER_MICRO

    private fun uuidBytes(value: Any): ByteArray {
        val uuid = when (value) {
            is UUID -> value
            is String -> UUID.fromString(value)
            else -> error("Cannot coerce ${value::class.simpleName} to UUID")
        }
        val buffer = ByteBuffer.allocate(UUID_BYTES)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private const val NANOS_PER_MICRO = 1_000L
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val UUID_BYTES = 16
}
