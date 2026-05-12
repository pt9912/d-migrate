package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.util.sha256Hex
import dev.dmigrate.driver.SqliteCastPreflightDeclaration

internal data class SqliteCastPreflightBinding(
    val operationId: String,
    val table: String,
    val column: String,
    val sourceType: NeutralType,
    val targetType: NeutralType,
    val countSql: String,
    val failingSql: String?,
) {
    val sourceTypeText: String = sourceType.toString()
    val targetTypeText: String = targetType.toString()
    val sqlHash: String = sha256Hex(countSql).take(16)
    val bindingKey: String = SqliteCastPreflightDeclaration.bindingKey(
        operationId = operationId,
        table = table,
        column = column,
        sourceType = sourceTypeText,
        targetType = targetTypeText,
        sqlHash = sqlHash,
    )
}

internal object SqliteCastPreflightSql {

    fun bindingsFor(diff: DiffResult): List<SqliteCastPreflightBinding> {
        val current = diff.currentSchema ?: return emptyList()
        val desired = diff.desiredSchema ?: return emptyList()
        val classification = SqliteRebuildPlanner.classify(diff.operations)
        val sql = SqliteDiffSqlBuilders()
        val out = mutableListOf<SqliteCastPreflightBinding>()
        for ((table, bucket) in classification.rebuildBuckets) {
            if (table !in current.tables || table !in desired.tables) continue
            for (op in bucket) {
                if (op !is DiffOperation.AlterColumnType) continue
                if (!SqliteCastMatrix.isWhitelisted(op.before, op.after)) continue
                val column = op.objectRef.path[1]
                out += bindingFor(op.id, table, column, op.before, op.after, sql)
            }
        }
        return out.sortedWith(compareBy({ it.table }, { it.column }, { it.operationId }))
    }

    fun bindingFor(
        operationId: String,
        table: String,
        column: String,
        sourceType: NeutralType,
        targetType: NeutralType,
        sql: SqliteDiffSqlBuilders,
    ): SqliteCastPreflightBinding {
        val quotedTable = sql.quote(table)
        val quotedColumn = sql.quote(column)
        val predicate = failingPredicate(sourceType, targetType, quotedColumn)
        val countSql = if (predicate == null) {
            "SELECT COUNT(*) AS total_rows FROM $quotedTable;"
        } else {
            "SELECT COUNT(*) AS failing_rows FROM $quotedTable WHERE $predicate;"
        }
        return SqliteCastPreflightBinding(
            operationId = operationId,
            table = table,
            column = column,
            sourceType = sourceType,
            targetType = targetType,
            countSql = countSql,
            failingSql = predicate,
        )
    }

    private fun failingPredicate(sourceType: NeutralType, targetType: NeutralType, quotedColumn: String): String? =
        when {
            isIntegerFamily(sourceType) && isIntegerFamily(targetType) ->
                "$quotedColumn IS NOT NULL AND typeof($quotedColumn) <> 'integer'"
            textTargetLimit(targetType) != null -> {
                val limit = textTargetLimit(targetType)!!
                "$quotedColumn IS NOT NULL AND length(CAST($quotedColumn AS TEXT)) > $limit"
            }
            sourceType is NeutralType.Date && targetType is NeutralType.DateTime && !targetType.timezone ->
                "$quotedColumn IS NOT NULL AND NOT (CAST($quotedColumn AS TEXT) GLOB " +
                    "'[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]')"
            else -> null
        }

    private fun isIntegerFamily(t: NeutralType): Boolean =
        t is NeutralType.SmallInt || t is NeutralType.Integer || t is NeutralType.BigInteger

    private fun textTargetLimit(t: NeutralType): Int? = when (t) {
        is NeutralType.Text -> t.maxLength
        is NeutralType.Char -> t.length
        else -> null
    }
}
