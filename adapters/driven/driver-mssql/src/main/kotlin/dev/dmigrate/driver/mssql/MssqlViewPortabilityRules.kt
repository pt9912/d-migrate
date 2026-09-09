package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ViewPortabilityContext
import dev.dmigrate.driver.ViewPortabilityRules
import dev.dmigrate.driver.ViewQueryClauseDetection
import dev.dmigrate.driver.ViewQueryFunctionSets

/**
 * Was SQL Server an einem fremden View-Rumpf nicht versteht.
 *
 * **Kein Umschreibregelwerk:** Rumpf-Texte passieren unveraendert; was T-SQL
 * nicht kennt, meldet [markers] als nicht portabel (E053 beim Aufrufer). Das
 * ist die bewusste Alternative zu einer halben Uebersetzung, die an der
 * naechsten Funktion scheitert.
 */
object MssqlViewPortabilityRules : ViewPortabilityRules {

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun markers(context: ViewPortabilityContext): List<String> {
        val markers = mutableListOf<String>()
        // T-SQL kennt weder `::` noch `||` (Verkettung ist `+`) noch eine
        // `LIMIT`-Klausel (`TOP` / `OFFSET … FETCH`) — alle drei sind harte
        // Syntaxfehler, unabhaengig vom Quelldialekt.
        if (context.codeOnly.contains("::")) markers += "PostgreSQL-style cast (::)"
        if (context.codeOnly.contains("||")) markers += "PostgreSQL/SQLite-style concatenation (||)"
        if (ViewQueryClauseDetection.hasLimitClause(context.tokens)) {
            markers += "LIMIT clause (T-SQL uses TOP / OFFSET … FETCH)"
        }
        if (ViewQueryClauseDetection.hasBareTopLevelOrderBy(context.tokens)) {
            markers += "ORDER BY in a view body without TOP/OFFSET (SQL Server Msg 1033)"
        }
        return markers
    }

    /**
     * Die Grundmenge traegt PG-/MySQL-/SQLite-Schreibweisen (`NOW()`,
     * `DATE_TRUNC`, `STRFTIME`, …), fuer die es keine T-SQL-Umschreibregel
     * gibt — sie muessen hier als unbekannt gelten, sonst landet ein solcher
     * Rumpf woertlich in `CREATE OR ALTER VIEW`.
     */
    override fun knownFunctions(): Set<String> =
        (ViewQueryFunctionSets.BASELINE - NOT_TSQL) + TSQL_ONLY

    /** Funktionsaufrufe, die T-SQL kennt. */
    private val TSQL_ONLY = setOf(
        "LEN", "GETDATE", "GETUTCDATE", "SYSDATETIME", "SYSDATETIMEOFFSET", "SYSUTCDATETIME",
        "DATEADD", "DATEDIFF", "DATEPART", "DATENAME", "DAY", "EOMONTH", "DATEFROMPARTS",
        "ISNULL", "IIF", "CONVERT", "TRY_CAST", "TRY_CONVERT", "NEWID", "FORMAT", "STR",
        "CHARINDEX", "PATINDEX", "STUFF", "REPLICATE", "REVERSE", "SPACE", "CONCAT_WS", "STRING_AGG",
        "LEFT", "RIGHT", "LTRIM", "RTRIM", "FLOOR", "CEILING", "POWER", "SQRT", "SIGN", "EXP",
        "LOG", "LOG10", "COUNT_BIG", "ISDATE", "ISNUMERIC", "ROW_NUMBER", "RANK", "DENSE_RANK",
        "NTILE", "LAG", "LEAD", "OVER", "PARTITION",
    )
    private val NOT_TSQL = setOf(
        "NOW", "DATE_TRUNC", "EXTRACT", "LENGTH", "CHAR_LENGTH", "DATE_FORMAT", "CURDATE", "CURTIME",
        "STRFTIME", "SUBSTR", "DATETIME", "DATE", "TIME", "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
    )
}
