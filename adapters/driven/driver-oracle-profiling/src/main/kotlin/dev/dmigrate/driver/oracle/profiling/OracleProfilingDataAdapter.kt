package dev.dmigrate.driver.oracle.profiling

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
 * Die Kennzahlen selbst, jede aus einer echten Abfrage.
 *
 * **`emptyStringCount` ist auf Oracle immer 0.** Ein Leerstring *ist* dort
 * NULL — die Werte stehen in `nullCount`. Das ist keine Luecke der
 * Umsetzung, sondern die Semantik des Servers; sie hier als 0 zu melden ist
 * die einzige wahrheitsgemaesse Antwort.
 *
 * Die Ausdruecke stehen in [OracleProfilingExpressions]; sie tragen die
 * LOB-Projektion und die ausgeschriebenen Zeitmasken.
 */
class OracleProfilingDataAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : ProfilingDataPort {

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().asJdbc().use { conn -> block(jdbcFactory(conn)) }

    // Geteilt mit den vier anderen Profiling-Adaptern: eine zweite
    // Quoting-Regel liefe irgendwann auseinander.
    private val sqlNames = ProfilingSqlNames(DatabaseDialect.ORACLE)

    private fun qt(table: String, schema: String?) = sqlNames.tablePath(table, schema)
    private fun qi(name: String) = sqlNames.identifier(name)

    override fun rowCount(pool: ConnectionPool, table: String, schema: String?): Long =
        withJdbc(pool) { jdbc ->
            (jdbc.querySingle("SELECT COUNT(*) AS cnt FROM ${qt(table, schema)}")!!["cnt"] as Number).toLong()
        }

    override fun columnMetrics(
        pool: ConnectionPool,
        table: String,
        column: String,
        dbType: String,
        schema: String?,
    ): ColumnMetrics {
        rejectUnprofilable(table, column, dbType)
        val t = qt(table, schema)
        val c = qi(column)
        val cmp = OracleProfilingExpressions.comparable(c, dbType)
        val display = OracleProfilingExpressions.display(c, dbType)
        val isText = OracleProfilingExpressions.isTextual(dbType)
        val textFields = if (isText) textFieldsSql(c, dbType) else ""

        return withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle(
                """
                SELECT
                    COUNT($cmp) AS non_null_count,
                    COUNT(*) - COUNT($cmp) AS null_count,
                    COUNT(DISTINCT $cmp) AS distinct_count,
                    GREATEST(COUNT($cmp) - COUNT(DISTINCT $cmp), 0) AS dup_count,
                    MIN($display) AS min_val,
                    MAX($display) AS max_val
                    $textFields
                FROM $t
                """.trimIndent(),
            )!!
            ColumnMetrics(
                nonNullCount = (row["non_null_count"] as Number).toLong(),
                nullCount = (row["null_count"] as Number).toLong(),
                distinctCount = (row["distinct_count"] as Number).toLong(),
                duplicateValueCount = (row["dup_count"] as Number).toLong(),
                // Ein Leerstring IST NULL in Oracle -- er kann hier nicht
                // gezaehlt werden und steht in `nullCount`.
                emptyStringCount = 0,
                blankStringCount = if (isText) (row["blank_count"] as? Number)?.toLong() ?: 0 else 0,
                minLength = if (isText) (row["min_len"] as? Number)?.toInt() else null,
                maxLength = if (isText) (row["max_len"] as? Number)?.toInt() else null,
                minValue = row["min_val"] as? String,
                maxValue = row["max_val"] as? String,
            )
        }
    }

    /**
     * Eine Spalte, die sich nicht aggregieren laesst, wird benannt
     * abgewiesen — sonst stuende im Fehler nur der Oracle-Code, und der
     * Betreiber saehe nicht, dass es am Typ liegt und nicht an seinen Daten.
     */
    private fun rejectUnprofilable(table: String, column: String, dbType: String) {
        OracleProfilingExpressions.unprofilableReason(dbType)?.let { reason ->
            throw UnsupportedOperationException(
                "Column '$table.$column' has Oracle type '$dbType', which cannot be profiled: $reason.",
            )
        }
    }

    /**
     * `TRIM('  ')` ergibt in Oracle den Leerstring und damit NULL — eine
     * Zeichenkette aus lauter Leerraum erkennt man genau daran, dass sie
     * nicht NULL ist, ihr getrimmter Wert aber schon.
     */
    private fun textFieldsSql(column: String, dbType: String): String {
        val len = OracleProfilingExpressions.length(column, dbType)
        val trimmable = if (OracleProfilingExpressions.isCharacterLob(dbType)) {
            "TO_CHAR(SUBSTR($column, 1, ${OracleProfilingExpressions.LOB_PROJECTION_CHARS}))"
        } else {
            column
        }
        return """
            , SUM(CASE WHEN $column IS NOT NULL AND TRIM($trimmable) IS NULL THEN 1 ELSE 0 END) AS blank_count
            , MIN($len) AS min_len
            , MAX($len) AS max_len
        """.trimIndent()
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
            // Zaehlung auf DERSELBEN geborgten Verbindung: ein zweites
            // rowCount(pool, …) borgte erneut und erschoepfte einen Pool der
            // Groesse 1.
            val total = (jdbc.querySingle("SELECT COUNT(*) AS cnt FROM $t")!!["cnt"] as Number).toLong().toDouble()
            if (total == 0.0) return@withJdbc emptyList()
            val dbType = columnTypeOf(jdbc, table, column, schema)
            rejectUnprofilable(table, column, dbType)
            // Gruppiert wird ueber denselben Ausdruck wie in `columnMetrics`,
            // sonst zaehlte diese Abfrage anders als der dort gemeldete
            // `distinctCount`.
            val display = OracleProfilingExpressions.display(c, dbType)
            jdbc.queryList(
                """
                SELECT $display AS val, COUNT(*) AS cnt
                FROM $t WHERE $c IS NOT NULL
                GROUP BY ${OracleProfilingExpressions.comparable(c, dbType)}
                ORDER BY COUNT(*) DESC, $display ASC
                FETCH FIRST ? ROWS ONLY
                """.trimIndent(),
                limit,
            ).map { row ->
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
                SELECT MIN($c) AS min_val, MAX($c) AS max_val, AVG($c) AS avg_val, SUM($c) AS sum_val,
                       STDDEV_POP($c) AS stddev_val,
                       SUM(CASE WHEN $c = 0 THEN 1 ELSE 0 END) AS zero_count,
                       SUM(CASE WHEN $c < 0 THEN 1 ELSE 0 END) AS neg_count
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
            // Die Maske haengt am Typ: `DATE` traegt keine Bruchteilsekunden,
            // `.FF` daran ist ORA-01821.
            val mask = OracleProfilingExpressions.temporalMask(columnTypeOf(jdbc, table, column, schema))
                ?: return@withJdbc null
            val row = jdbc.querySingle(
                """
                SELECT TO_CHAR(MIN($c), '$mask') AS min_ts, TO_CHAR(MAX($c), '$mask') AS max_ts
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
            val dbType = columnTypeOf(jdbc, table, column, schema)
            rejectUnprofilable(table, column, dbType)
            targetTypes.map { targetType -> compatibility(jdbc, t, c, dbType, targetType) }
        }
    }

    /** Wie in [OracleSchemaIntrospectionAdapter]: der Owner ist gefaltet. */
    private fun ownerOf(schema: String?): String? = when {
        schema == null -> null
        schema.startsWith("\"") && schema.endsWith("\"") && schema.length > 1 ->
            schema.substring(1, schema.length - 1)
        else -> schema.uppercase()
    }

    private fun columnTypeOf(jdbc: JdbcOperations, table: String, column: String, schema: String?): String =
        jdbc.querySingle(
            """
            SELECT data_type FROM all_tab_columns
            WHERE owner = COALESCE(?, SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA'))
              AND table_name = ? AND column_name = ?
            """.trimIndent(),
            ownerOf(schema), table, column,
        )?.get("data_type")?.toString().orEmpty()

    private fun compatibility(
        jdbc: JdbcOperations,
        table: String,
        column: String,
        dbType: String,
        targetType: TargetLogicalType,
    ): TargetTypeCompatibility {
        val fits = OracleProfilingExpressions.fits(column, dbType, targetType)
        val text = OracleProfilingExpressions.display(column, dbType)
        val row = jdbc.querySingle(
            """
            SELECT COUNT(*) AS checked,
                   SUM(CASE WHEN $fits THEN 1 ELSE 0 END) AS compat,
                   SUM(CASE WHEN $fits THEN 0 ELSE 1 END) AS incompat
            FROM $table WHERE $column IS NOT NULL
            """.trimIndent(),
        )!!
        val incompatible = (row["incompat"] as? Number)?.toLong() ?: 0
        val examples = if (incompatible > 0) exampleValues(jdbc, table, column, text, fits) else emptyList()
        return TargetTypeCompatibility(
            targetType,
            (row["checked"] as Number).toLong(),
            (row["compat"] as? Number)?.toLong() ?: 0,
            incompatible,
            examples,
            DeterminationStatus.FULL_SCAN,
        )
    }

    private fun exampleValues(
        jdbc: JdbcOperations,
        table: String,
        column: String,
        text: String,
        fits: String,
    ): List<String> = jdbc.queryList(
        """
        SELECT DISTINCT $text AS val FROM $table
        WHERE $column IS NOT NULL AND NOT ($fits)
        ORDER BY val ASC
        FETCH FIRST 3 ROWS ONLY
        """.trimIndent(),
    ).map { it["val"].toString() }
}
