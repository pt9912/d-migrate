package dev.dmigrate.driver.migration

/**
 * Classifies a migration SQL stream by which side owns the database
 * transaction during execution.
 */
object MigrationStreamClassifier {

    /**
     * True iff any statement in the stream is rendered with
     * `transactionScope = STREAM_OWNED`.
     */
    fun streamOwnsTransaction(statements: List<MigrationDdlStatement>): Boolean =
        statements.any { it.transactionScope == TransactionScope.STREAM_OWNED }

    /**
     * Execute guard for the current executor, which can run one coherent
     * ownership model at a time.
     */
    fun unsupportedTransactionScopeReason(statements: List<MigrationDdlStatement>): String? {
        if (statements.isEmpty()) return null
        val scopes = statements.map { it.transactionScope }.toSet()
        return when {
            TransactionScope.NO_TRANSACTION in scopes ->
                "NO_TRANSACTION statements require a dedicated execution strategy"
            scopes.size > 1 ->
                "mixed transaction scopes are not executable as one migration stream"
            scopes.single() == TransactionScope.STREAM_OWNED &&
                statements.any { it.hints.transactionBehavior == TransactionBehavior.UNKNOWN } ->
                "stream-owned transaction boundaries are not fully described"
            else -> null
        }
    }
}
