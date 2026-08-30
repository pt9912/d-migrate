package dev.dmigrate.driver.mssql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.profiling.ProfilingSqlNames
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.model.NumericStats
import dev.dmigrate.profiling.model.TargetTypeCompatibility
import dev.dmigrate.profiling.model.TemporalStats
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.types.TargetLogicalType
import java.sql.Connection

/**
 * Aggregat-Abfragen fuer `data profile` gegen SQL Server.
 *
 * Zwei T-SQL-Eigenheiten praegen die Formen hier:
 *
 * - **Ziel-Typ-Vertraeglichkeit prueft `TRY_CONVERT`, kein regulaerer
 *   Ausdruck.** T-SQL hat keine Regex; `TRY_CONVERT` liefert `NULL` statt zu
 *   scheitern und beantwortet damit genau die gestellte Frage — laesst sich der
 *   Wert konvertieren —, statt sie ueber ein Muster zu naehern.
 * - **`GREATEST` gibt es erst ab SQL Server 2022**, die Untergrenze ist 2017
 *   ([ADR 0047]). Der Deckel bei null steht deshalb als `CASE`.
 *
 * `LEN` zaehlt ohne nachlaufende Leerzeichen — das ist die Zeichenlaenge, die
 * ein Anwender meint. Der Test auf die **leere** Zeichenkette laeuft trotzdem
 * ueber `DATALENGTH`: T-SQL fuellt beim Vergleich mit Leerzeichen auf, `'   '
 * = ''` ist dort wahr, und ein naives `= ''` zaehlte jeden Leerraum-Wert als
 * leer statt als blank.
 *
 * **Sechs Typen sind in T-SQL nicht vergleichbar** und weisen `COUNT(DISTINCT)`,
 * `GROUP BY` und `ORDER BY` mit „is not comparable" bzw. „invalid for count
 * operator" ab: `geometry`, `geography`, `xml` und die LOB-Alttypen `text`,
 * `ntext`, `image`. Die Aggregate laufen deshalb auf einer Projektion, nicht auf
 * der Spalte. `image` braucht dabei einen eigenen Weg — es laesst sich gar nicht
 * nach `nvarchar` wandeln („Explicit conversion … is not allowed"), sondern nur
 * ueber `varbinary`.
 */
class MssqlProfilingDataAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : ProfilingDataPort {

    private val sqlNames = ProfilingSqlNames(DatabaseDialect.MSSQL)

    private fun qi(name: String): String = sqlNames.identifier(name)
    private fun qt(table: String, schema: String?): String = sqlNames.tablePath(table, schema)

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().asJdbc().use { conn -> block(jdbcFactory(conn)) }

    /**
     * Der Wert als Text fuers Profil. Bytefolgen erscheinen hexadezimal
     * (`0x01`) statt als Steuerzeichen — Stil 1 von `CONVERT`.
     *
     * `NVARCHAR(MAX)` statt einer festen Breite: bei 4000 Zeichen fielen zwei
     * lange Werte mit gleichem Anfang in der Anzeige zusammen, waehrend die
     * Gruppierung sie trennte — `topValues` lieferte denselben Text zweimal.
     * SQL Server gruppiert und sortiert ueber `NVARCHAR(MAX)`.
     */
    private fun displayExpr(column: String, dbType: String): String =
        if (baseType(dbType) in BINARY_TYPES) {
            "CONVERT(NVARCHAR(MAX), CONVERT(VARBINARY(MAX), $column), 1)"
        } else {
            "CAST($column AS NVARCHAR(MAX))"
        }

    /**
     * Der Ausdruck, ueber den sich zaehlen und gruppieren laesst. Fuer die
     * nicht vergleichbaren Typen ist das die Projektion, sonst die Spalte
     * selbst — sie traegt die genauere Unterscheidung. Nicht nur
     * `COUNT(DISTINCT)` braucht ihn: `COUNT` allein weist `text`, `ntext` und
     * `image` ebenso ab.
     */
    private fun comparableExpr(column: String, dbType: String): String =
        if (baseType(dbType) in NOT_COMPARABLE) displayExpr(column, dbType) else column

    /**
     * Der Ausdruck fuer Laengen- und Leerraum-Metriken. `text` und `ntext`
     * weisen `LEN`, `TRIM` und den Vergleich mit `''` ab.
     */
    private fun lengthExpr(column: String, dbType: String): String =
        if (baseType(dbType) in LEGACY_TEXT) "CAST($column AS NVARCHAR(MAX))" else column

    private fun baseType(dbType: String): String =
        dbType.lowercase().trim().substringBefore('(').trim()

    override fun rowCount(pool: ConnectionPool, table: String, schema: String?): Long =
        withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle("SELECT count(*) AS cnt FROM ${qt(table, schema)}")!!
            (row["cnt"] as Number).toLong()
        }

    override fun columnMetrics(
        pool: ConnectionPool,
        table: String,
        column: String,
        dbType: String,
        schema: String?,
    ): ColumnMetrics {
        val t = qt(table, schema)
        val c = qi(column)
        val isText = baseType(dbType).let { it.contains("char") || it.contains("text") }
        val len = lengthExpr(c, dbType)
        val textFields = if (isText) {
            """
            , sum(case when DATALENGTH($len) = 0 then 1 else 0 end) AS empty_count
            , sum(case when DATALENGTH($len) > 0 AND TRIM($len) = '' then 1 else 0 end) AS blank_count
            , min(LEN($len)) AS min_len
            , max(LEN($len)) AS max_len
            """.trimIndent()
        } else {
            ""
        }
        val cmp = comparableExpr(c, dbType)
        val display = displayExpr(c, dbType)

        return withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle(
                """
                SELECT
                    count($cmp) AS non_null_count,
                    count(*) - count($cmp) AS null_count,
                    count(distinct $cmp) AS distinct_count,
                    case when count($cmp) - count(distinct $cmp) > 0
                         then count($cmp) - count(distinct $cmp) else 0 end AS dup_count,
                    min($display) AS min_val,
                    max($display) AS max_val
                    $textFields
                FROM $t
                """.trimIndent(),
            )!!
            ColumnMetrics(
                nonNullCount = (row["non_null_count"] as Number).toLong(),
                nullCount = (row["null_count"] as Number).toLong(),
                distinctCount = (row["distinct_count"] as Number).toLong(),
                duplicateValueCount = (row["dup_count"] as Number).toLong(),
                // sum(case …) ueber 0 Zeilen liefert NULL, nicht 0.
                emptyStringCount = if (isText) (row["empty_count"] as? Number)?.toLong() ?: 0 else 0,
                blankStringCount = if (isText) (row["blank_count"] as? Number)?.toLong() ?: 0 else 0,
                minLength = if (isText) (row["min_len"] as? Number)?.toInt() else null,
                maxLength = if (isText) (row["max_len"] as? Number)?.toInt() else null,
                minValue = row["min_val"] as? String,
                maxValue = row["max_val"] as? String,
            )
        }
    }

    override fun topValues(
        pool: ConnectionPool,
        table: String,
        column: String,
        limit: Int,
        schema: String?,
    ): List<ValueFrequency> {
        val t = qt(table, schema)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            // Zaehlung auf DERSELBEN geborgten Verbindung — ein zweites
            // rowCount(pool, …) borgte erneut und erschoepfte einen Pool der
            // Groesse 1.
            val total = (jdbc.querySingle("SELECT count(*) AS cnt FROM $t")!!["cnt"] as Number).toLong().toDouble()
            if (total == 0.0) return@withJdbc emptyList()
            // Der Port reicht den Typ hier nicht durch; ohne ihn liesse sich die
            // Projektion nicht waehlen, und eine `geometry`-Spalte brachte das
            // GROUP BY zu Fall.
            val dbType = columnTypeOf(jdbc, table, column, schema)
            val display = displayExpr(c, dbType)
            // Gruppiert wird ueber denselben Ausdruck wie in `columnMetrics`,
            // sonst zaehlte diese Abfrage anders als der dort gemeldete
            // `distinctCount`: zwei `nvarchar(max)`-Werte mit gleichen ersten
            // 4000 Zeichen fielen in der Projektion zusammen.
            val rows = jdbc.queryList(
                """
                SELECT TOP (?) $display AS val, count(*) AS cnt
                FROM $t WHERE $c IS NOT NULL
                GROUP BY ${comparableExpr(c, dbType)} ORDER BY cnt DESC, val ASC
                """.trimIndent(),
                limit,
            )
            rows.map { row ->
                val cnt = (row["cnt"] as Number).toLong()
                ValueFrequency(row["val"] as? String, cnt, cnt / total)
            }
        }
    }

    override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?): NumericStats? {
        val t = qt(table, schema)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle(
                """
                SELECT min($c) AS min_val, max($c) AS max_val,
                       avg(CAST($c AS FLOAT)) AS avg_val, sum(CAST($c AS FLOAT)) AS sum_val,
                       STDEVP(CAST($c AS FLOAT)) AS stddev_val,
                       sum(case when $c = 0 then 1 else 0 end) AS zero_count,
                       sum(case when $c < 0 then 1 else 0 end) AS neg_count
                FROM $t WHERE $c IS NOT NULL
                """.trimIndent(),
            ) ?: return@withJdbc null
            NumericStats(
                min = (row["min_val"] as? Number)?.toDouble(),
                max = (row["max_val"] as? Number)?.toDouble(),
                avg = (row["avg_val"] as? Number)?.toDouble(),
                sum = (row["sum_val"] as? Number)?.toDouble(),
                stddev = (row["stddev_val"] as? Number)?.toDouble(),
                zeroCount = (row["zero_count"] as? Number)?.toLong() ?: 0,
                negativeCount = (row["neg_count"] as? Number)?.toLong() ?: 0,
            )
        }
    }

    override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?): TemporalStats? {
        val t = qt(table, schema)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            // Stil 126 ist ISO 8601 — dieselbe Textform, die der
            // PostgreSQL-Profiler liefert.
            val row = jdbc.querySingle(
                """
                SELECT CONVERT(NVARCHAR(33), min($c), 126) AS min_ts,
                       CONVERT(NVARCHAR(33), max($c), 126) AS max_ts
                FROM $t WHERE $c IS NOT NULL
                """.trimIndent(),
            ) ?: return@withJdbc null
            TemporalStats(row["min_ts"] as? String, row["max_ts"] as? String)
        }
    }

    override fun targetTypeCompatibility(
        pool: ConnectionPool,
        table: String,
        column: String,
        targetTypes: List<TargetLogicalType>,
        schema: String?,
    ): List<TargetTypeCompatibility> {
        val t = qt(table, schema)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            val text = displayExpr(c, columnTypeOf(jdbc, table, column, schema))
            targetTypes.map { targetType -> compatibility(jdbc, t, c, targetType, text) }
        }
    }

    private fun columnTypeOf(jdbc: JdbcOperations, table: String, column: String, schema: String?): String =
        jdbc.querySingle(
            """
            SELECT ty.name AS type_name
            FROM sys.columns c
            JOIN sys.types ty ON ty.user_type_id = c.user_type_id
            JOIN sys.tables t ON t.object_id = c.object_id
            JOIN sys.schemas s ON s.schema_id = t.schema_id
            WHERE s.name = COALESCE(?, SCHEMA_NAME()) AND t.name = ? AND c.name = ?
            """.trimIndent(),
            schema, table, column,
        )?.get("type_name")?.toString().orEmpty()

    private fun compatibility(
        jdbc: JdbcOperations,
        table: String,
        column: String,
        targetType: TargetLogicalType,
        text: String,
    ): TargetTypeCompatibility {
        val fits = fitsExpression(text, targetType)
        val row = jdbc.querySingle(
            """
            SELECT count(*) AS checked,
                   sum(case when $fits then 1 else 0 end) AS compat,
                   sum(case when $fits then 0 else 1 end) AS incompat
            FROM $table WHERE $column IS NOT NULL
            """.trimIndent(),
        )!!
        val incompatible = (row["incompat"] as? Number)?.toLong() ?: 0
        val examples = if (incompatible > 0) {
            jdbc.queryList(
                """
                SELECT DISTINCT TOP (3) $text AS val FROM $table
                WHERE $column IS NOT NULL AND NOT ($fits)
                ORDER BY val ASC
                """.trimIndent(),
            ).map { it["val"].toString() }
        } else {
            emptyList()
        }
        return TargetTypeCompatibility(
            targetType,
            (row["checked"] as Number).toLong(),
            (row["compat"] as? Number)?.toLong() ?: 0,
            incompatible,
            examples,
            DeterminationStatus.FULL_SCAN,
        )
    }

    /**
     * Ob ein Wert in den Zieltyp passt. `TRY_CONVERT` liefert `NULL` statt zu
     * scheitern; `BOOLEAN` fragt zusaetzlich die Textformen ab, die andere
     * Dialekte fuer Wahrheitswerte fuehren, denn `bit` nimmt nur 0 und 1.
     */
    private fun fitsExpression(text: String, targetType: TargetLogicalType): String {
        // T-SQL wandelt die leere und die reine Leerraum-Zeichenkette in `0`
        // statt in NULL. Ohne diesen Vorbehalt gaelte eine Spalte aus lauter
        // leeren Zeichenketten als vollstaendig zahl-vertraeglich — und stuende
        // zugleich in `emptyStringCount`.
        val nonBlank = "LEN(TRIM($text)) > 0"
        return when (targetType) {
            TargetLogicalType.INTEGER -> "$nonBlank AND TRY_CONVERT(BIGINT, $text) IS NOT NULL"
            TargetLogicalType.DECIMAL -> "$nonBlank AND TRY_CONVERT(FLOAT, $text) IS NOT NULL"
            TargetLogicalType.BOOLEAN ->
                "LOWER($text) IN ('0','1','true','false','yes','no')"
            // Feste Stile statt der Voreinstellung: ohne Stil richtet sich die
            // Wandlung nach der Sprache der Anmeldung. `13/02/2024` ist unter
            // `british` ein Datum und unter `us_english` keins — das Profil
            // derselben Datenbank fiele je nach Rechner anders aus. Zugelassen
            // sind die beiden eindeutigen Formen: 126 (ISO 8601, `2024-02-13`)
            // und 112 (`20240213`). Die mehrdeutige Tagesform faellt bei beiden
            // durch.
            TargetLogicalType.DATE -> isoDateFits("DATE", text)
            TargetLogicalType.DATETIME -> isoDateFits("DATETIME2", text)
            TargetLogicalType.STRING -> "1 = 1"
        }
    }

    private fun isoDateFits(target: String, text: String): String =
        "(TRY_CONVERT($target, $text, 126) IS NOT NULL OR TRY_CONVERT($target, $text, 112) IS NOT NULL)"

    private companion object {
        /** Nicht vergleichbar: kein `COUNT(DISTINCT)`, `GROUP BY` oder `ORDER BY`. */
        val NOT_COMPARABLE = setOf("geometry", "geography", "xml", "text", "ntext", "image")
        val BINARY_TYPES = setOf("binary", "varbinary", "image")
        /** LOB-Alttypen, die `LEN`, `TRIM` und den Vergleich mit `''` abweisen. */
        val LEGACY_TEXT = setOf("text", "ntext")
    }
}
