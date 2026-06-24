package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * AP6.1 (ADR 0020): MySQL-Reverse-Capture der Partitionierung aus
 * `information_schema.PARTITIONS`. Erfasst die **MySQL-native** Form:
 * - RANGE: `PARTITION_DESCRIPTION` = `VALUES LESS THAN`-Obergrenze → `to` (kein `from`;
 *   MySQL-RANGE ist kontiguierlich, die untere Grenze wird nicht gespeichert — die
 *   `from`-Rekonstruktion für PG-Ziele ist AP6.5).
 * - LIST: `PARTITION_DESCRIPTION` = `VALUES IN`-Liste → `values`.
 * - HASH: keine Grenze; nur benannte Kinder (modulus/remainder-Rekonstruktion = AP6.5).
 *
 * Der Parser (Klammer-/Quote-bewusstes Top-Level-Splitting) ist der Hotspot —
 * analog zum PG-`PostgresPartitionBoundParser`, aber für MySQLs `information_schema`-Form.
 */
internal object MysqlPartitionReader {

    fun read(session: JdbcOperations, schemaName: String, table: String): PartitionConfig? {
        val rows = MysqlMetadataQueries.listPartitions(session, schemaName, table)
        if (rows.isEmpty()) return null
        val method = (rows.first()["partition_method"] as? String)?.uppercase() ?: return null
        val type = when {
            method.startsWith("RANGE") -> PartitionType.RANGE
            method.startsWith("LIST") -> PartitionType.LIST
            method.contains("HASH") || method.contains("KEY") -> PartitionType.HASH
            else -> return null
        }
        val key = parseKey(rows.first()["partition_expression"] as? String)
        val partitions = rows.mapNotNull { row -> parsePartition(row, type) }
        return PartitionConfig(type = type, key = key, partitions = partitions)
    }

    /** `PARTITION_EXPRESSION` (`` `payment_date` `` bzw. `` `a`,`b` ``) → Spaltenliste (Backticks gestrippt). */
    private fun parseKey(expression: String?): List<String> {
        if (expression.isNullOrBlank()) return emptyList()
        return splitTopLevel(expression).map { it.trim().removeSurrounding("`") }
    }

    private fun parsePartition(row: Map<String, Any?>, type: PartitionType): PartitionDefinition? {
        val name = row["partition_name"] as? String ?: return null
        val description = (row["partition_description"] as? String)?.trim()
        return when (type) {
            PartitionType.RANGE -> PartitionDefinition(name = name, to = parseRangeUpperBound(description))
            PartitionType.LIST -> PartitionDefinition(name = name, values = description?.let { splitTopLevel(it) })
            PartitionType.HASH -> PartitionDefinition(name = name)
        }
    }

    /** `VALUES LESS THAN`-Wert(e): `MAXVALUE` → Sentinel, sonst Literal; mehrspaltig = Tupel. */
    private fun parseRangeUpperBound(description: String?): List<PartitionBound>? {
        if (description.isNullOrBlank()) return null
        return splitTopLevel(description).map { value ->
            if (value.equals("MAXVALUE", ignoreCase = true)) PartitionBound.MaxValue
            else PartitionBound.Value(value)
        }
    }

    /** Komma-Trennung auf Top-Level (respektiert `'`-/`` ` ``-Quotes + Klammern). */
    private fun splitTopLevel(s: String): List<String> {
        val parts = mutableListOf<String>()
        val token = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in s) {
            when {
                quote != null -> { token.append(c); if (c == quote) quote = null }
                c == '\'' || c == '`' -> { quote = c; token.append(c) }
                c == '(' -> { depth++; token.append(c) }
                c == ')' -> { depth--; token.append(c) }
                c == ',' && depth == 0 -> { parts.addTrimmed(token); token.clear() }
                else -> token.append(c)
            }
        }
        parts.addTrimmed(token)
        return parts
    }

    private fun MutableList<String>.addTrimmed(token: StringBuilder) {
        val trimmed = token.toString().trim()
        if (trimmed.isNotEmpty()) add(trimmed)
    }
}
