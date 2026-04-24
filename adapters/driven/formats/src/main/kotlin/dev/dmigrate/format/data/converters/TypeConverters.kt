package dev.dmigrate.format.data.converters

import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.format.data.ConversionContext
import dev.dmigrate.format.data.DeserializerHelpers
import dev.dmigrate.format.data.TypeConverter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.Base64
import java.util.BitSet
import java.util.UUID

// ─── String / Char / CLOB ─────────────────────────────────────

internal object StringConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): String = when (value) {
        is String -> value
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }
}

// ─── Boolean ───────────────────────────────────────────────────

internal object BooleanConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): Any {
        val sqlTypeName = ctx.hint.sqlTypeName?.uppercase()
        if (sqlTypeName != null && DeserializerHelpers.isMultiBit(sqlTypeName)) {
            return BitSetConverter.convert(ctx, value)
        }
        return when (value) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw ImportSchemaMismatchException(
                    "column '${ctx.columnName}' expects BOOLEAN (true/false, case-insensitive), got '$value'"
                )
            }
            is Number -> throw ImportSchemaMismatchException(
                "column '${ctx.columnName}' expects BOOLEAN, got number $value — " +
                    "0/1/yes/no are not accepted, use explicit true/false"
            )
            else -> throw ImportSchemaMismatchException(
                "column '${ctx.columnName}' expects BOOLEAN, got ${value::class.simpleName}"
            )
        }
    }
}

// ─── Integer-Familie ───────────────────────────────────────────

internal object LongConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Number -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects integer, got non-integer number $value " +
                "(source token had decimal point or exponent)"
        )
        is String -> {
            val trimmed = value.trim()
            val direct = trimmed.toLongOrNull()
            if (direct != null) {
                direct
            } else {
                val asDouble = trimmed.toDoubleOrNull()
                if (asDouble != null) {
                    throw ImportSchemaMismatchException(
                        "column '${ctx.columnName}' expects integer, got decimal string '$value'"
                    )
                }
                trimmed.toLong() // throws NumberFormatException
            }
        }
        is Boolean -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects integer, got boolean $value"
        )
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects integer, got ${value::class.simpleName}"
        )
    }
}

// ─── Floating-point ────────────────────────────────────────────

internal object DoubleConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): Double = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        is String -> value.trim().toDouble()
        is Boolean -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects floating-point, got boolean $value"
        )
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects floating-point, got ${value::class.simpleName}"
        )
    }
}

// ─── Exact numeric ─────────────────────────────────────────────

internal object NumericDecimalConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): Any = when {
        ctx.isCsvSource -> toBigDecimal(ctx.columnName, value)
        (ctx.hint.scale ?: 0) > 0 -> toBigDecimal(ctx.columnName, value)
        (ctx.hint.precision ?: 0) > 18 -> toBigDecimal(ctx.columnName, value)
        value is Double || value is Float -> toBigDecimal(ctx.columnName, value)
        value is BigDecimal -> {
            val canonical = value.stripTrailingZeros()
            if (canonical.scale() <= 0) {
                DeserializerHelpers.parseExactLong(ctx.hint, ctx.columnName, canonical.toPlainString())
            } else {
                value
            }
        }
        value is Long || value is Int || value is Short || value is Byte ->
            DeserializerHelpers.checkedLong(ctx.hint, ctx.columnName, (value as Number).toLong())
        value is String -> {
            val trimmed = value.trim()
            if (DeserializerHelpers.looksDecimalToken(trimmed)) {
                BigDecimal(trimmed)
            } else {
                DeserializerHelpers.parseExactLong(ctx.hint, ctx.columnName, trimmed)
            }
        }
        else -> toBigDecimal(ctx.columnName, value)
    }

    private fun toBigDecimal(columnName: String, value: Any): BigDecimal = when (value) {
        is BigDecimal -> value
        is Long, is Int, is Short, is Byte -> BigDecimal((value as Number).toLong())
        is Double -> BigDecimal(value.toString())
        is Float -> BigDecimal(value.toString())
        is Number -> BigDecimal(value.toString())
        is String -> BigDecimal(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '$columnName' expects NUMERIC/DECIMAL, got ${value::class.simpleName}"
        )
    }
}

// ─── Datum / Zeit ──────────────────────────────────────────────

internal object LocalDateConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): LocalDate = when (value) {
        is LocalDate -> value
        is String -> LocalDate.parse(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects DATE, got ${value::class.simpleName}"
        )
    }
}

internal object LocalTimeConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): LocalTime = when (value) {
        is LocalTime -> value
        is String -> LocalTime.parse(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects TIME, got ${value::class.simpleName}"
        )
    }
}

internal object LocalDateTimeConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): LocalDateTime = when (value) {
        is LocalDateTime -> value
        is String -> {
            val trimmed = value.trim()
            if (DeserializerHelpers.hasOffsetOrZone(trimmed)) {
                throw ImportSchemaMismatchException(
                    "column '${ctx.columnName}' expects TIMESTAMP without time zone, " +
                        "got '$value' with explicit offset/zone"
                )
            }
            LocalDateTime.parse(trimmed)
        }
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects TIMESTAMP, got ${value::class.simpleName}"
        )
    }
}

internal object OffsetDateTimeConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): OffsetDateTime = when (value) {
        is OffsetDateTime -> value
        is String -> OffsetDateTime.parse(value.trim())
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects TIMESTAMP WITH TIME ZONE, got ${value::class.simpleName}"
        )
    }
}

// ─── UUID / OTHER ──────────────────────────────────────────────

internal object OtherTypeConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): Any {
        val sqlTypeName = ctx.hint.sqlTypeName?.lowercase() ?: ""
        return when {
            sqlTypeName == "uuid" -> when (value) {
                is UUID -> value
                is String -> UUID.fromString(value.trim())
                else -> throw ImportSchemaMismatchException(
                    "column '${ctx.columnName}' expects UUID, got ${value::class.simpleName}"
                )
            }
            sqlTypeName == "json" || sqlTypeName == "jsonb" -> when (value) {
                is String -> value
                is Map<*, *>, is List<*> -> DeserializerHelpers.simpleJsonString(value)
                else -> value.toString()
            }
            sqlTypeName == "interval" -> when (value) {
                is String -> value
                else -> value.toString()
            }
            sqlTypeName == "xml" -> when (value) {
                is String -> value
                else -> value.toString()
            }
            else -> value
        }
    }
}

// ─── Binär / BLOB ──────────────────────────────────────────────

internal object ByteArrayConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): ByteArray = when (value) {
        is ByteArray -> value
        is String -> try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw ImportSchemaMismatchException(
                "column '${ctx.columnName}' expects BINARY/BLOB (Base64), got invalid Base64 string"
            )
        }
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects BINARY/BLOB, got ${value::class.simpleName}"
        )
    }
}

// ─── Array ─────────────────────────────────────────────────────

internal object ListConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): List<Any?> = when (value) {
        is List<*> -> value.toList()
        is Array<*> -> value.toList()
        is String -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects ARRAY, got plain string " +
                "(use a JSON/YAML array literal, not a comma-separated list)"
        )
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects ARRAY, got ${value::class.simpleName}"
        )
    }
}

// ─── BitSet (MySQL BIT(N>1)) ───────────────────────────────────

internal object BitSetConverter : TypeConverter {
    override fun convert(ctx: ConversionContext, value: Any): BitSet = when (value) {
        is String -> {
            val trimmed = value.trim()
            if (trimmed.any { it != '0' && it != '1' }) {
                throw ImportSchemaMismatchException(
                    "column '${ctx.columnName}' expects BIT(N) string of 0/1, got '$value'"
                )
            }
            val bits = BitSet(trimmed.length)
            val len = trimmed.length
            for (i in 0 until len) {
                if (trimmed[len - 1 - i] == '1') bits.set(i)
            }
            bits
        }
        is BitSet -> value
        else -> throw ImportSchemaMismatchException(
            "column '${ctx.columnName}' expects BIT(N) string, got ${value::class.simpleName}"
        )
    }
}
