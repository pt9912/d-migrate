package dev.dmigrate.driver.data

/**
 * Stable JDBC type code constants for port contracts that already expose
 * `jdbcType: Int`.
 *
 * This intentionally mirrors the numeric values of `java.sql.Types` without
 * importing `java.sql` into hexagon application code. It is the narrow G1
 * interop exception accepted in ADR 0028, not a neutral type model.
 */
object JdbcTypeCodes {
    const val BIT = -7
    const val BIGINT = -5
    const val LONGVARBINARY = -4
    const val VARBINARY = -3
    const val BINARY = -2
    const val LONGVARCHAR = -1
    const val CHAR = 1
    const val NUMERIC = 2
    const val DECIMAL = 3
    const val INTEGER = 4
    const val SMALLINT = 5
    const val FLOAT = 6
    const val REAL = 7
    const val DOUBLE = 8
    const val VARCHAR = 12
    const val BOOLEAN = 16
    const val DATE = 91
    const val TIME = 92
    const val TIMESTAMP = 93
    const val OTHER = 1111
    const val ARRAY = 2003
    const val BLOB = 2004
    const val CLOB = 2005
    const val SQLXML = 2009
    const val NCHAR = -15
    const val NVARCHAR = -9
    const val LONGNVARCHAR = -16
    const val TIME_WITH_TIMEZONE = 2013
    const val TIMESTAMP_WITH_TIMEZONE = 2014
}
