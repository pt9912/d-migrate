package dev.dmigrate.driver

/**
 * Transforms SQL functions in view queries between dialects.
 * Uses simple regex-based string substitution (no SQL parser).
 */
class ViewQueryTransformer(private val targetDialect: DatabaseDialect) {

    fun transform(query: String, sourceDialect: String?): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        var result = query

        for (rule in getRules()) {
            val regex = rule.pattern.toRegex(RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(result)) {
                result = regex.replace(result, rule.replacement)
            }
        }

        // Check for potentially untransformed dialect-specific functions
        if (sourceDialect != null && sourceDialect != targetDialect.name.lowercase()) {
            val unknownFunctions = detectUnknownFunctions(result)
            if (unknownFunctions.isNotEmpty()) {
                notes += TransformationNote(
                    type = NoteType.WARNING,
                    code = "W111",
                    objectName = "view_query",
                    message = "View query may contain dialect-specific functions: ${unknownFunctions.joinToString(", ")}",
                    hint = "Review and manually adjust if needed."
                )
            }
        }

        return result to notes
    }

    private data class TransformRule(val pattern: String, val replacement: String)

    private fun getRules(): List<TransformRule> = when (targetDialect) {
        DatabaseDialect.MYSQL -> mysqlRules
        DatabaseDialect.SQLITE -> sqliteRules
        DatabaseDialect.POSTGRESQL -> postgresRules
    }

    private val mysqlRules = listOf(
        // Date/time functions
        TransformRule("""DATE_TRUNC\s*\(\s*'month'\s*,\s*(\w+)\s*\)""", """DATE_FORMAT($1, '%Y-%m-01')"""),
        TransformRule("""DATE_TRUNC\s*\(\s*'year'\s*,\s*(\w+)\s*\)""", """DATE_FORMAT($1, '%Y-01-01')"""),
        TransformRule("""DATE_TRUNC\s*\(\s*'day'\s*,\s*(\w+)\s*\)""", """DATE($1)"""),
        TransformRule("""EXTRACT\s*\(\s*YEAR\s+FROM\s+(\w+)\s*\)""", """YEAR($1)"""),
        TransformRule("""EXTRACT\s*\(\s*MONTH\s+FROM\s+(\w+)\s*\)""", """MONTH($1)"""),
        // String functions
        TransformRule("""SUBSTRING\s*\(\s*(\w+)\s+FROM\s+(\d+)\s+FOR\s+(\d+)\s*\)""", """SUBSTRING($1, $2, $3)"""),
        TransformRule("""\bLENGTH\s*\(""", """CHAR_LENGTH("""),
        // Date/time literals
        TransformRule("""\bCURRENT_DATE\b""", """CURDATE()"""),
        TransformRule("""\bCURRENT_TIME\b""", """CURTIME()"""),
        // Boolean literals
        TransformRule("""\bTRUE\b""", "1"),
        TransformRule("""\bFALSE\b""", "0"),
    )

    private val sqliteRules = listOf(
        // Date/time functions
        TransformRule("""NOW\s*\(\s*\)""", """datetime('now')"""),
        TransformRule("""CURRENT_TIMESTAMP""", """datetime('now')"""),
        TransformRule("""CURRENT_DATE""", """date('now')"""),
        TransformRule("""CURRENT_TIME""", """time('now')"""),
        TransformRule("""DATE_TRUNC\s*\(\s*'month'\s*,\s*(\w+)\s*\)""", """strftime('%Y-%m-01', $1)"""),
        TransformRule("""DATE_TRUNC\s*\(\s*'year'\s*,\s*(\w+)\s*\)""", """strftime('%Y-01-01', $1)"""),
        TransformRule("""DATE_TRUNC\s*\(\s*'day'\s*,\s*(\w+)\s*\)""", """date($1)"""),
        TransformRule("""EXTRACT\s*\(\s*YEAR\s+FROM\s+(\w+)\s*\)""", """CAST(strftime('%Y', $1) AS INTEGER)"""),
        TransformRule("""EXTRACT\s*\(\s*MONTH\s+FROM\s+(\w+)\s*\)""", """CAST(strftime('%m', $1) AS INTEGER)"""),
        // String functions
        TransformRule("""CONCAT\s*\(\s*(\w+)\s*,\s*(\w+)\s*\)""", """$1 || $2"""),
        TransformRule("""SUBSTRING\s*\(\s*(\w+)\s+FROM\s+(\d+)\s+FOR\s+(\d+)\s*\)""", """SUBSTR($1, $2, $3)"""),
        // Boolean literals
        TransformRule("""\bTRUE\b""", "1"),
        TransformRule("""\bFALSE\b""", "0"),
    )

    private val postgresRules = listOf(
        // PostgreSQL is usually the source; minimal transformations needed
        TransformRule("""NOW\s*\(\s*\)""", "CURRENT_TIMESTAMP"),
    )

    private val transparentFunctions = setOf(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "UPPER", "LOWER",
        "TRIM", "REPLACE", "COALESCE", "NULLIF", "CAST",
    )

    private val allKnownFunctions = (mysqlRules + sqliteRules + postgresRules)
        .flatMap { Regex("[A-Z_]+").findAll(it.pattern).map { m -> m.value } }
        .toSet() + transparentFunctions + setOf(
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
    )

    private fun detectUnknownFunctions(query: String): List<String> {
        // Find function-like patterns: WORD(
        return Regex("""([A-Z_][A-Z0-9_]*)\s*\(""", RegexOption.IGNORE_CASE)
            .findAll(query)
            .map { it.groupValues[1].uppercase() }
            .filter { it !in allKnownFunctions }
            .distinct()
            .toList()
    }
}
