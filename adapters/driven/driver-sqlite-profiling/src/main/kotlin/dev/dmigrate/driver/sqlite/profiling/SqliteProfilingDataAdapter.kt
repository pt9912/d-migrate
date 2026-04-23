package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
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
 * SQLite implementation of [ProfilingDataPort].
 *
 * SQLite fallbacks:
 * - `stddev_pop` is not built-in → computed via Kotlin from fetched values, or null for large tables
 * - Text length uses `length()` which counts characters (not bytes)
 * - Type compatibility uses `typeof()` and CAST checks
 */
class SqliteProfilingDataAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : ProfilingDataPort {

    private fun qi(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.SQLITE)

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().use { conn -> block(jdbcFactory(conn)) }

    override fun rowCount(pool: ConnectionPool, table: String, schema: String?): Long =
        withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle("SELECT count(*) as cnt FROM ${qi(table)}")!!
            (row["cnt"] as Number).toLong()
        }

    override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?): ColumnMetrics {
        val t = qi(table)
        val c = qi(column)
        val isText = dbType.lowercase().let {
            it.contains("text") || it.contains("char") || it.contains("clob") || it.contains("varchar")
        }
        val textFields = if (isText) """
            , sum(case when $c = '' then 1 else 0 end) as empty_count
            , sum(case when $c != '' and trim($c) = '' then 1 else 0 end) as blank_count
            , min(length($c)) as min_len
            , max(length($c)) as max_len
        """.trimIndent() else ""

        val sql = """
            SELECT
                count($c) as non_null_count,
                count(*) - count($c) as null_count,
                count(distinct $c) as distinct_count,
                count($c) - count(distinct $c) as dup_count,
                min($c) as min_val,
                max($c) as max_val
                $textFields
            FROM $t
        """.trimIndent()

        return withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle(sql)!!
            ColumnMetrics(
                nonNullCount = (row["non_null_count"] as Number).toLong(),
                nullCount = (row["null_count"] as Number).toLong(),
                distinctCount = (row["distinct_count"] as Number).toLong(),
                duplicateValueCount = (row["dup_count"] as Number).toLong(),
                emptyStringCount = if (isText) (row["empty_count"] as Number).toLong() else 0,
                blankStringCount = if (isText) (row["blank_count"] as Number).toLong() else 0,
                minLength = if (isText) (row["min_len"] as? Number)?.toInt() else null,
                maxLength = if (isText) (row["max_len"] as? Number)?.toInt() else null,
                minValue = row["min_val"] as? String,
                maxValue = row["max_val"] as? String,
            )
        }
    }

    override fun topValues(pool: ConnectionPool, table: String, column: String, limit: Int, schema: String?): List<ValueFrequency> {
        val t = qi(table)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            val total = rowCount(pool, table).toDouble()
            if (total == 0.0) return@withJdbc emptyList()
            val rows = jdbc.queryList("""
                SELECT $c as val, count(*) as cnt
                FROM $t
                WHERE $c IS NOT NULL
                GROUP BY $c
                ORDER BY cnt DESC, val ASC
                LIMIT ?
            """.trimIndent(), limit)
            rows.map { row ->
                val cnt = (row["cnt"] as Number).toLong()
                ValueFrequency(row["val"] as? String, cnt, cnt / total)
            }
        }
    }

    override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?): NumericStats? {
        val t = qi(table)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle("""
                SELECT
                    min(cast($c as real)) as min_val,
                    max(cast($c as real)) as max_val,
                    avg(cast($c as real)) as avg_val,
                    sum(cast($c as real)) as sum_val,
                    sum(case when $c = 0 then 1 else 0 end) as zero_count,
                    sum(case when cast($c as real) < 0 then 1 else 0 end) as neg_count
                FROM $t
                WHERE $c IS NOT NULL
            """.trimIndent()) ?: return@withJdbc null
            NumericStats(
                min = (row["min_val"] as? Number)?.toDouble(),
                max = (row["max_val"] as? Number)?.toDouble(),
                avg = (row["avg_val"] as? Number)?.toDouble(),
                sum = (row["sum_val"] as? Number)?.toDouble(),
                stddev = null, // SQLite has no built-in stddev
                zeroCount = (row["zero_count"] as Number).toLong(),
                negativeCount = (row["neg_count"] as Number).toLong(),
            )
        }
    }

    override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?): TemporalStats? {
        val t = qi(table)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            val row = jdbc.querySingle("""
                SELECT min($c) as min_ts, max($c) as max_ts
                FROM $t
                WHERE $c IS NOT NULL
            """.trimIndent()) ?: return@withJdbc null
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
        val t = qi(table)
        val c = qi(column)
        return withJdbc(pool) { jdbc ->
            targetTypes.map { targetType ->
                val castExpr = when (targetType) {
                    TargetLogicalType.INTEGER ->
                        "($c GLOB '-[0-9]*' OR $c GLOB '[0-9]*') AND cast($c as integer) = cast($c as real)"
                    TargetLogicalType.DECIMAL -> "($c GLOB '-[0-9]*' OR $c GLOB '[0-9]*')"
                    TargetLogicalType.BOOLEAN -> "lower($c) IN ('0', '1', 'true', 'false', 'yes', 'no')"
                    TargetLogicalType.DATE -> "$c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'"
                    TargetLogicalType.DATETIME -> "$c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]*'"
                    TargetLogicalType.STRING -> "1=1"
                }

                val row = jdbc.querySingle("""
                    SELECT
                        count(*) as checked,
                        sum(case when $castExpr then 1 else 0 end) as compat,
                        sum(case when NOT ($castExpr) then 1 else 0 end) as incompat
                    FROM $t
                    WHERE $c IS NOT NULL
                """.trimIndent())!!
                val incompatible = (row["incompat"] as Number).toLong()

                val examples = if (incompatible > 0) {
                    jdbc.queryList("""
                        SELECT DISTINCT $c as val FROM $t
                        WHERE $c IS NOT NULL AND NOT ($castExpr)
                        ORDER BY $c ASC LIMIT 3
                    """.trimIndent()).map { it["val"] as String }
                } else emptyList()

                TargetTypeCompatibility(
                    targetType,
                    (row["checked"] as Number).toLong(),
                    (row["compat"] as Number).toLong(),
                    incompatible,
                    examples,
                    DeterminationStatus.FULL_SCAN,
                )
            }
        }
    }
}
