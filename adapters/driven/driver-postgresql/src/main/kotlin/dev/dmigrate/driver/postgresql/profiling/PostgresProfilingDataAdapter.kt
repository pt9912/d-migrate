package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.model.NumericStats
import dev.dmigrate.profiling.model.TargetTypeCompatibility
import dev.dmigrate.profiling.model.TemporalStats
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.types.TargetLogicalType

class PostgresProfilingDataAdapter : ProfilingDataPort {

    private fun qi(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.POSTGRESQL)

    private fun qt(table: String, schema: String?): String =
        if (schema != null) "${qi(schema)}.${qi(table)}" else qi(table)

    override fun rowCount(pool: ConnectionPool, table: String, schema: String?): Long {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT count(*) FROM ${qt(table, schema)}")
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?): ColumnMetrics {
        val t = qt(table, schema)
        val c = qi(column)
        pool.borrow().use { conn ->
            val isText = dbType.lowercase().let {
                it.contains("char") || it.contains("text") || it == "name" || it == "citext"
            }
            val textFields = if (isText) """
                , sum(case when $c = '' then 1 else 0 end)::bigint as empty_count
                , sum(case when $c <> '' and trim($c) = '' then 1 else 0 end)::bigint as blank_count
                , min(char_length($c)) as min_len
                , max(char_length($c)) as max_len
            """.trimIndent() else ""

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT
                        count($c) as non_null_count,
                        count(*) - count($c) as null_count,
                        count(distinct $c) as distinct_count,
                        greatest(count($c) - count(distinct $c), 0) as dup_count,
                        min($c::text) as min_val,
                        max($c::text) as max_val
                        $textFields
                    FROM $t
                """.trimIndent())
                rs.next()
                return ColumnMetrics(
                    nonNullCount = rs.getLong("non_null_count"),
                    nullCount = rs.getLong("null_count"),
                    distinctCount = rs.getLong("distinct_count"),
                    duplicateValueCount = rs.getLong("dup_count"),
                    emptyStringCount = if (isText) rs.getLong("empty_count") else 0,
                    blankStringCount = if (isText) rs.getLong("blank_count") else 0,
                    minLength = if (isText) rs.getObject("min_len")?.toString()?.toIntOrNull() else null,
                    maxLength = if (isText) rs.getObject("max_len")?.toString()?.toIntOrNull() else null,
                    minValue = rs.getString("min_val"),
                    maxValue = rs.getString("max_val"),
                )
            }
        }
    }

    override fun topValues(pool: ConnectionPool, table: String, column: String, limit: Int, schema: String?): List<ValueFrequency> {
        val t = qt(table, schema)
        val c = qi(column)
        pool.borrow().use { conn ->
            val total = rowCount(pool, table, schema).toDouble()
            if (total == 0.0) return emptyList()
            conn.prepareStatement("""
                SELECT $c::text as val, count(*) as cnt
                FROM $t
                WHERE $c IS NOT NULL
                GROUP BY $c
                ORDER BY cnt DESC, val ASC
                LIMIT ?
            """.trimIndent()).use { ps ->
                ps.setInt(1, limit)
                val rs = ps.executeQuery()
                val result = mutableListOf<ValueFrequency>()
                while (rs.next()) {
                    result += ValueFrequency(rs.getString("val"), rs.getLong("cnt"), rs.getLong("cnt") / total)
                }
                return result
            }
        }
    }

    override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?): NumericStats? {
        val t = qt(table, schema)
        val c = qi(column)
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT
                        min($c::numeric)::double precision as min_val,
                        max($c::numeric)::double precision as max_val,
                        avg($c::numeric)::double precision as avg_val,
                        sum($c::numeric)::double precision as sum_val,
                        stddev_pop($c::numeric)::double precision as stddev_val,
                        count(case when $c::numeric = 0 then 1 end) as zero_count,
                        count(case when $c::numeric < 0 then 1 end) as neg_count
                    FROM $t
                    WHERE $c IS NOT NULL
                """.trimIndent())
                if (!rs.next()) return null
                return NumericStats(
                    min = rs.getObject("min_val")?.toString()?.toDoubleOrNull(),
                    max = rs.getObject("max_val")?.toString()?.toDoubleOrNull(),
                    avg = rs.getObject("avg_val")?.toString()?.toDoubleOrNull(),
                    sum = rs.getObject("sum_val")?.toString()?.toDoubleOrNull(),
                    stddev = rs.getObject("stddev_val")?.toString()?.toDoubleOrNull(),
                    zeroCount = rs.getLong("zero_count"),
                    negativeCount = rs.getLong("neg_count"),
                )
            }
        }
    }

    override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?): TemporalStats? {
        val t = qt(table, schema)
        val c = qi(column)
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT min($c)::text as min_ts, max($c)::text as max_ts
                    FROM $t WHERE $c IS NOT NULL
                """.trimIndent())
                if (!rs.next()) return null
                return TemporalStats(rs.getString("min_ts"), rs.getString("max_ts"))
            }
        }
    }

    override fun targetTypeCompatibility(
        pool: ConnectionPool, table: String, column: String, targetTypes: List<TargetLogicalType>, schema: String?,
    ): List<TargetTypeCompatibility> {
        val t = qt(table, schema)
        val c = qi(column)
        pool.borrow().use { conn ->
            return targetTypes.map { targetType ->
                val castExpr = when (targetType) {
                    TargetLogicalType.INTEGER -> """($c::text ~ '^-?[0-9]+$')"""
                    TargetLogicalType.DECIMAL -> """($c::text ~ '^-?[0-9]+(\\.[0-9]+)?$')"""
                    TargetLogicalType.BOOLEAN -> """(lower($c::text) IN ('0','1','true','false','yes','no','t','f'))"""
                    TargetLogicalType.DATE -> """($c::text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')"""
                    TargetLogicalType.DATETIME -> """($c::text ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}')"""
                    TargetLogicalType.STRING -> "(true)"
                }

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("""
                        SELECT count(*) as checked,
                               sum(case when $castExpr then 1 else 0 end) as compat,
                               sum(case when NOT $castExpr then 1 else 0 end) as incompat
                        FROM $t WHERE $c IS NOT NULL
                    """.trimIndent())
                    rs.next()
                    val incompatible = rs.getLong("incompat")

                    val examples = if (incompatible > 0) {
                        conn.createStatement().use { s2 ->
                            val ers = s2.executeQuery("""
                                SELECT DISTINCT $c::text as val FROM $t
                                WHERE $c IS NOT NULL AND NOT $castExpr
                                ORDER BY val ASC LIMIT 3
                            """.trimIndent())
                            val ex = mutableListOf<String>()
                            while (ers.next()) ex += ers.getString("val")
                            ex
                        }
                    } else emptyList()

                    TargetTypeCompatibility(targetType, rs.getLong("checked"), rs.getLong("compat"), incompatible, examples, DeterminationStatus.FULL_SCAN)
                }
            }
        }
    }
}
