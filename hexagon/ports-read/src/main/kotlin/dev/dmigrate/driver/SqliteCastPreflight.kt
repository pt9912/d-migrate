package dev.dmigrate.driver

/**
 * Live-data preflight status for SQLite RebuildTable casts.
 *
 * These entries are produced before rendering `schema migrate --execute`
 * and consumed by the SQLite diff renderer before it emits the
 * `INSERT ... SELECT CAST(...)` copy step. File/plan-only runs do not
 * execute live SQL and therefore surface [NOT_RUN_FILE_TARGET] rather
 * than pretending the data has passed.
 */
data class SqliteCastPreflightDeclaration(
    val operationId: String,
    val table: String,
    val column: String,
    val sourceType: String,
    val targetType: String,
    val status: SqliteCastPreflightStatus,
    val sqlHash: String,
    val totalRows: Long? = null,
    val failingRows: Long? = null,
    val sampleRowIds: List<String> = emptyList(),
    val problem: String? = null,
) {
    val bindingKey: String
        get() = bindingKey(operationId, table, column, sourceType, targetType, sqlHash)

    companion object {
        fun bindingKey(
            operationId: String,
            table: String,
            column: String,
            sourceType: String,
            targetType: String,
            sqlHash: String,
        ): String = listOf(operationId, table, column, sourceType, targetType, sqlHash).joinToString("\u001f")
    }
}

enum class SqliteCastPreflightStatus {
    PASSED,
    FAILED,
    NOT_RUN_FILE_TARGET,
    NOT_RUN_POLICY,
}
