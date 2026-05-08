package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlStatement
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote

internal class MysqlIndexPartitionDdlHelper(
    private val quoteIdentifier: (String) -> String,
) {

    fun generatePartitionClause(
        partitioning: PartitionConfig,
        notes: MutableList<TransformationNote>,
    ): String {
        if (partitioning.type == PartitionType.RANGE) {
            notes += TransformationNote(
                type = NoteType.WARNING,
                code = "W112",
                objectName = partitioning.key.joinToString(","),
                message = "RANGE partition expressions may need manual adjustment for MySQL (e.g., wrapping date columns with YEAR()).",
                hint = "Review the partition key expressions and adjust for MySQL-specific syntax if needed.",
            )
        }

        val key = partitioning.key.joinToString(", ") { quoteIdentifier(it) }
        return buildString {
            append("PARTITION BY ${partitioning.type.name} ($key)")
            if (partitioning.partitions.isNotEmpty()) {
                append(" (\n")
                append(
                    partitioning.partitions.joinToString(",\n") { partition ->
                        buildString {
                            append("    PARTITION ${quoteIdentifier(partition.name)}")
                            when (partitioning.type) {
                                PartitionType.RANGE -> append(" VALUES LESS THAN (${partition.to})")
                                PartitionType.LIST -> {
                                    val values = partition.values?.joinToString(", ") ?: ""
                                    append(" VALUES IN ($values)")
                                }
                                PartitionType.HASH -> Unit
                            }
                        }
                    }
                )
                append("\n)")
            }
        }
    }

    fun generateIndices(tableName: String, table: TableDefinition): List<DdlStatement> =
        generatedIndexNames(tableName, table.indices).mapIndexedNotNull { position, indexName ->
            generateIndex(tableName, table.indices[position], indexName)
        }

    private fun generateIndex(tableName: String, index: IndexDefinition, indexName: String): DdlStatement? {
        val columns = index.columns.joinToString(", ") { renderIndexColumn(it) }

        return when (index.type) {
            IndexType.GIN, IndexType.GIST, IndexType.BRIN -> {
                DdlStatement(
                    "",
                    listOf(
                        TransformationNote(
                            type = NoteType.WARNING,
                            code = "W102",
                            objectName = indexName,
                            message = "${index.type.name} index '$indexName' is not supported in MySQL and was skipped.",
                            hint = "Consider using a BTREE index or FULLTEXT index instead.",
                        )
                    )
                )
            }
            IndexType.HASH -> {
                val sql = buildString {
                    append("CREATE ")
                    if (index.unique) append("UNIQUE ")
                    append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
                    append(" USING BTREE")
                    append(" ($columns);")
                }
                DdlStatement(
                    sql,
                    listOf(
                        TransformationNote(
                            type = NoteType.WARNING,
                            code = "W102",
                            objectName = indexName,
                            message = "HASH index '$indexName' is not supported on InnoDB; converted to BTREE.",
                            hint = "InnoDB only supports BTREE indexes. The HASH index has been automatically converted.",
                        )
                    )
                )
            }
            IndexType.BTREE -> {
                val sql = buildString {
                    append("CREATE ")
                    if (index.unique) append("UNIQUE ")
                    append("INDEX ${quoteIdentifier(indexName)} ON ${quoteIdentifier(tableName)}")
                    append(" ($columns);")
                }
                DdlStatement(sql)
            }
        }
    }

    private fun renderIndexColumn(column: IndexColumn): String =
        buildString {
            val direction = column.direction
            append(quoteIdentifier(column.name))
            if (direction != null) append(" ${direction.name}")
        }

    private fun generatedIndexNames(tableName: String, indices: List<IndexDefinition>): List<String> {
        val baseNames = indices.map { index ->
            index.name ?: "idx_${tableName}_${index.columnNames.joinToString("_")}"
        }
        val baseCounts = baseNames.groupingBy { it }.eachCount()
        val used = indices.mapNotNull { it.name }.groupingBy { it }.eachCount().toMutableMap()
        return indices.mapIndexed { position, index ->
            index.name ?: disambiguateGeneratedIndexName(baseNames[position], index, baseCounts.getValue(baseNames[position]), used)
        }
    }

    private fun disambiguateGeneratedIndexName(
        baseName: String,
        index: IndexDefinition,
        baseCount: Int,
        used: MutableMap<String, Int>,
    ): String {
        val candidate = if (baseCount == 1) baseName else "${baseName}_${indexDirectionSuffix(index)}"
        val seen = used.getOrDefault(candidate, 0)
        used[candidate] = seen + 1
        return if (seen == 0) candidate else "${candidate}_${seen + 1}"
    }

    private fun indexDirectionSuffix(index: IndexDefinition): String =
        index.columns.joinToString("_") { it.direction?.name?.lowercase() ?: "default" }
}
