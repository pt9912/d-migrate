package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.types.LogicalType

/**
 * Oracle-Typname auf die logische Profiling-Familie.
 *
 * Oracle fuehrt bis 23ai keine Wahrheitsspalte: die Konvention ist
 * `NUMBER(1)`, und der Typname allein sagt nicht, ob sie gemeint war — die
 * Praezision steht hier nicht zur Verfuegung. `NUMBER` faellt deshalb
 * durchgehend auf [LogicalType.DECIMAL], nicht auf `BOOLEAN`; wer die
 * 0/1-Konvention profilieren will, sieht sie an den Wertehaeufigkeiten.
 *
 * `DATE` traegt in Oracle eine Uhrzeit und ist damit [LogicalType.DATETIME],
 * nicht `DATE` — dieselbe Entscheidung wie im Reverse-Pfad.
 */
class OracleLogicalTypeResolver : LogicalTypeResolverPort {

    override fun resolve(dbType: String): LogicalType {
        val normalized = dbType.uppercase().trim().substringBefore('(').trim()
        if (normalized.isEmpty()) return LogicalType.UNKNOWN
        // `TIMESTAMP(6) WITH TIME ZONE` und Verwandte tragen ihre Genauigkeit
        // und die Zonenangabe im Namen.
        if (normalized.startsWith("TIMESTAMP")) return LogicalType.DATETIME
        if (normalized.startsWith("INTERVAL")) return LogicalType.STRING
        return NUMERIC[normalized]
            ?: TEMPORAL[normalized]
            ?: OPAQUE[normalized]
            ?: if (normalized in TEXTUAL) LogicalType.STRING else LogicalType.UNKNOWN
    }

    private companion object {
        val NUMERIC = mapOf(
            "NUMBER" to LogicalType.DECIMAL,
            "FLOAT" to LogicalType.DECIMAL,
            "BINARY_FLOAT" to LogicalType.DECIMAL,
            "BINARY_DOUBLE" to LogicalType.DECIMAL,
            // `INTEGER`/`SMALLINT` stehen nicht dabei: Oracle legt sie als
            // NUMBER an, und `ALL_TAB_COLUMNS.DATA_TYPE` meldet auch NUMBER.
            "BOOLEAN" to LogicalType.BOOLEAN,
        )
        val TEMPORAL = mapOf("DATE" to LogicalType.DATETIME)
        val OPAQUE = mapOf(
            "RAW" to LogicalType.BINARY,
            "LONG RAW" to LogicalType.BINARY,
            "BLOB" to LogicalType.BINARY,
            "BFILE" to LogicalType.BINARY,
            "JSON" to LogicalType.JSON,
            "XMLTYPE" to LogicalType.STRING,
            "SDO_GEOMETRY" to LogicalType.GEOMETRY,
        )
        val TEXTUAL = setOf("VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "CLOB", "NCLOB", "LONG", "ROWID", "UROWID")
    }
}
