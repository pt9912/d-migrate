package dev.dmigrate.format.data.converters

import dev.dmigrate.format.data.TypeConverter
import java.sql.Types

/**
 * Maps JDBC type codes to [TypeConverter] singletons.
 *
 * The registry is the single source of truth for the type-dispatch
 * that [dev.dmigrate.format.data.ValueDeserializer] uses. Adding
 * support for a new JDBC type only requires registering a new
 * converter here.
 */
internal object TypeConverterRegistry {

    private val converters: Map<Int, TypeConverter> = buildMap {
        // String family
        for (type in intArrayOf(
            Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
            Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
            Types.CLOB, Types.NCLOB, Types.SQLXML,
        )) {
            put(type, StringConverter)
        }

        // Boolean / BIT
        put(Types.BOOLEAN, BooleanConverter)
        put(Types.BIT, BooleanConverter)

        // Integer family
        for (type in intArrayOf(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT)) {
            put(type, LongConverter)
        }

        // Floating-point
        for (type in intArrayOf(Types.REAL, Types.FLOAT, Types.DOUBLE)) {
            put(type, DoubleConverter)
        }

        // Exact numeric
        put(Types.NUMERIC, NumericDecimalConverter)
        put(Types.DECIMAL, NumericDecimalConverter)

        // Date / Time
        put(Types.DATE, LocalDateConverter)
        put(Types.TIME, LocalTimeConverter)
        put(Types.TIME_WITH_TIMEZONE, LocalTimeConverter)
        put(Types.TIMESTAMP, LocalDateTimeConverter)
        put(Types.TIMESTAMP_WITH_TIMEZONE, OffsetDateTimeConverter)

        // OTHER (PG: UUID, JSON, JSONB, INTERVAL, XML)
        put(Types.OTHER, OtherTypeConverter)

        // Binary / BLOB
        for (type in intArrayOf(Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB)) {
            put(type, ByteArrayConverter)
        }

        // Array
        put(Types.ARRAY, ListConverter)
    }

    /** Returns the converter for [jdbcType], or `null` for unknown/passthrough types. */
    fun converterFor(jdbcType: Int): TypeConverter? = converters[jdbcType]
}
