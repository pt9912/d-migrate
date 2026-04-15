package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.model.NumericStats
import dev.dmigrate.profiling.model.TargetTypeCompatibility
import dev.dmigrate.profiling.model.TemporalStats
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.types.TargetLogicalType

class MysqlProfilingDataAdapter : ProfilingDataPort {

    override fun rowCount(pool: ConnectionPool, table: String): Long {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT count(*) FROM `$table`")
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String): ColumnMetrics {
        pool.borrow().use { conn ->
            val isText = dbType.lowercase().let {
                it.contains("char") || it.contains("text") || it.contains("enum") || it.contains("set")
            }
            val textFields = if (isText) """
                , sum(case when `$column` = '' then 1 else 0 end) as empty_count
                , sum(case when `$column` <> '' and trim(`$column`) = '' then 1 else 0 end) as blank_count
                , min(char_length(`$column`)) as min_len
                , max(char_length(`$column`)) as max_len
            """.trimIndent() else ""

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT
                        count(`$column`) as non_null_count,
                        count(*) - count(`$column`) as null_count,
                        count(distinct `$column`) as distinct_count,
                        greatest(count(`$column`) - count(distinct `$column`), 0) as dup_count,
                        min(cast(`$column` as char)) as min_val,
                        max(cast(`$column` as char)) as max_val
                        $textFields
                    FROM `$table`
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

    override fun topValues(pool: ConnectionPool, table: String, column: String, limit: Int): List<ValueFrequency> {
        pool.borrow().use { conn ->
            val total = rowCount(pool, table).toDouble()
            if (total == 0.0) return emptyList()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT cast(`$column` as char) as val, count(*) as cnt
                    FROM `$table` WHERE `$column` IS NOT NULL
                    GROUP BY `$column` ORDER BY cnt DESC, val ASC LIMIT $limit
                """.trimIndent())
                val result = mutableListOf<ValueFrequency>()
                while (rs.next()) {
                    result += ValueFrequency(rs.getString("val"), rs.getLong("cnt"), rs.getLong("cnt") / total)
                }
                return result
            }
        }
    }

    override fun numericStats(pool: ConnectionPool, table: String, column: String): NumericStats? {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT min(`$column`) as min_val, max(`$column`) as max_val,
                           avg(`$column`) as avg_val, sum(`$column`) as sum_val,
                           stddev_pop(`$column`) as stddev_val,
                           sum(case when `$column` = 0 then 1 else 0 end) as zero_count,
                           sum(case when `$column` < 0 then 1 else 0 end) as neg_count
                    FROM `$table` WHERE `$column` IS NOT NULL
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

    override fun temporalStats(pool: ConnectionPool, table: String, column: String): TemporalStats? {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT min(`$column`) as min_ts, max(`$column`) as max_ts
                    FROM `$table` WHERE `$column` IS NOT NULL
                """.trimIndent())
                if (!rs.next()) return null
                return TemporalStats(rs.getString("min_ts"), rs.getString("max_ts"))
            }
        }
    }

    override fun targetTypeCompatibility(
        pool: ConnectionPool, table: String, column: String, targetTypes: List<TargetLogicalType>,
    ): List<TargetTypeCompatibility> {
        pool.borrow().use { conn ->
            return targetTypes.map { targetType ->
                val castExpr = when (targetType) {
                    TargetLogicalType.INTEGER -> """(cast(`$column` as char) REGEXP '^-?[0-9]+$')"""
                    TargetLogicalType.DECIMAL -> """(cast(`$column` as char) REGEXP '^-?[0-9]+(\\\\.[0-9]+)?$')"""
                    TargetLogicalType.BOOLEAN -> """(lower(cast(`$column` as char)) IN ('0','1','true','false','yes','no'))"""
                    TargetLogicalType.DATE -> """(cast(`$column` as char) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}$')"""
                    TargetLogicalType.DATETIME -> """(cast(`$column` as char) REGEXP '^[0-9]{4}-[0-9]{2}-[0-9]{2}')"""
                    TargetLogicalType.STRING -> "(1=1)"
                }

                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("""
                        SELECT count(*) as checked,
                               sum(case when $castExpr then 1 else 0 end) as compat,
                               sum(case when NOT $castExpr then 1 else 0 end) as incompat
                        FROM `$table` WHERE `$column` IS NOT NULL
                    """.trimIndent())
                    rs.next()
                    val incompatible = rs.getLong("incompat")

                    val examples = if (incompatible > 0) {
                        conn.createStatement().use { s2 ->
                            val ers = s2.executeQuery("""
                                SELECT DISTINCT cast(`$column` as char) as val FROM `$table`
                                WHERE `$column` IS NOT NULL AND NOT $castExpr LIMIT 3
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
