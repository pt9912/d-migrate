package dev.dmigrate.driver.data

/**
 * Normalises a value read from one driver's JDBC result set into a form a foreign
 * target driver can bind, so cross-dialect transfer does not hand the target
 * driver an object it cannot serialise.
 *
 * Two source-driver wrapper types would otherwise make the target JDBC driver
 * fall back to Java serialisation (binding `\xAC\xED…` instead of the value):
 * - [java.sql.Array] (e.g. PostgreSQL `text[]`) → JSON array string (K1).
 * - pgjdbc's `org.postgresql.util.PGobject` (e.g. `tsvector`, `json`) → its
 *   `getValue()` string (L1). Detected reflectively by package + method so this
 *   module needs no compile dependency on pgjdbc.
 *
 * Scalars (String, Number, Boolean, temporal, byte[]) and anything the target
 * binds natively are returned unchanged.
 */
object JdbcForeignValueNormalizer {

    fun normalize(value: Any): Any = when {
        value is java.sql.Array -> JdbcArrayJsonEncoder.encode(value)
        else -> pgObjectValue(value) ?: value
    }

    /** The string value of a pgjdbc `PGobject` (or subclass), or null if not one. */
    private fun pgObjectValue(value: Any): String? {
        if (!value.javaClass.name.startsWith("org.postgresql.")) return null
        return runCatching {
            val getValue = value.javaClass.getMethod("getValue")
            if (getValue.returnType == String::class.java) getValue.invoke(value) as? String else null
        }.getOrNull()
    }
}
