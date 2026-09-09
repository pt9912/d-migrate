package dev.dmigrate.driver

/**
 * Die dialektneutrale Grundmenge bekannter Namen — Schluesselwoerter und
 * Funktionen, die in jedem der fuenf Dialekte dasselbe heissen.
 *
 * Was ein einzelner Dialekt darueber hinaus kennt oder gerade **nicht** kennt,
 * steht bei ihm (`<Dialekt>ViewPortabilityRules`), nicht hier.
 */
object ViewQueryFunctionSets {

    private val transparentFunctions = setOf(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "UPPER", "LOWER",
        "TRIM", "REPLACE", "COALESCE", "NULLIF", "CAST",
    )

    private val sqlKeywords = setOf(
        "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
        "ON", "AS", "AND", "OR", "NOT", "IN", "BETWEEN", "LIKE", "IS", "NULL",
        "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "DISTINCT",
        "UNION", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END",
        "ASC", "DESC", "FOR", "EACH", "ROW", "STATEMENT",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "VIEW", "INDEX", "WITH",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "CHECK",
        "DEFAULT", "UNIQUE", "AUTO_INCREMENT", "AUTOINCREMENT",
        "INTEGER", "INT", "TEXT", "REAL", "BLOB", "VARCHAR", "CHAR",
        "BOOLEAN", "DECIMAL", "FLOAT", "DOUBLE", "DATE", "TIME", "TIMESTAMP",
        "SERIAL", "BIGINT", "SMALLINT", "TINYINT", "JSON", "JSONB", "UUID",
        "TRUE", "FALSE", "ALL", "ANY", "SOME",
        "NOW", "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
        "DATE_TRUNC", "EXTRACT", "SUBSTRING", "LENGTH", "CHAR_LENGTH",
        "CONCAT", "YEAR", "MONTH", "DATE", "DATE_FORMAT", "CURDATE", "CURTIME",
        "STRFTIME", "SUBSTR", "DATETIME",
    )

    /** Schluesselwoerter und Funktionen, die jeder Dialekt gleich schreibt. */
    val BASELINE: Set<String> = transparentFunctions + sqlKeywords

    /**
     * Skalar- und Aggregatfunktionen, die **MySQL und PostgreSQL** gleich
     * schreiben und gleich meinen. Hier und nicht bei einem der beiden, weil
     * sie zu zweit gehoert — ein Treiber, der auf den anderen zeigt, waere
     * eine Abhaengigkeit, die es zwischen Dialekten nicht geben soll.
     *
     * **Nicht** fuer SQLite: mehrere davon brauchen dort die optionale
     * Mathematik-Erweiterung, und das Urteil bleibt konservativ.
     */
    val PORTABLE_MYSQL_POSTGRES: Set<String> = setOf(
        "FLOOR", "CEIL", "CEILING", "MOD", "POWER", "SQRT", "SIGN", "EXP", "LN", "LOG",
        "GREATEST", "LEAST", "LTRIM", "RTRIM",
    )
}
