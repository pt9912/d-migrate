package dev.dmigrate.driver.migration

/**
 * Classifies a migration SQL stream by which side owns the database
 * transaction during execution.
 */
/**
 * Wie ein Strom ausgefuehrt werden muss. Die Entscheidung steht hier, weil sie
 * zwei Ausfuehrer haben: den produktiven JDBC-Ausfuehrer und die Test-Fixture,
 * die dieselben Streams gegen echte Datenbanken faehrt. Zweimal geschrieben
 * driftet sie — `NO_TRANSACTION` gab es einmal nur in einer der beiden Kopien,
 * und der Live-Test lief die Anweisung deshalb in der Transaktion, die sie
 * gerade nicht vertraegt.
 */
enum class StreamExecutionModel {
    /** Der Ausfuehrer klammert alles in eine Transaktion. */
    RUNNER_TRANSACTION,

    /** Der Strom bringt seine Grenzen selbst mit (SQLite-Rebuild). */
    STREAM_TRANSACTION,

    /** Keine Transaktion — die Datenbank lehnt diese Anweisungen darin ab. */
    NO_TRANSACTION,
}

object MigrationStreamClassifier {

    /**
     * Das Ausfuehrungsmodell eines **einheitlichen** Abschnitts. Gemischte
     * Streams trennt `segmentForExecute` vorher am Scope-Wechsel; hier kommt
     * nur an, was zusammen laufen kann.
     */
    fun executionModel(statements: List<MigrationDdlStatement>): StreamExecutionModel = when {
        statements.isNotEmpty() && statements.all { it.transactionScope == TransactionScope.NO_TRANSACTION } ->
            StreamExecutionModel.NO_TRANSACTION
        streamOwnsTransaction(statements) -> StreamExecutionModel.STREAM_TRANSACTION
        else -> StreamExecutionModel.RUNNER_TRANSACTION
    }

    /**
     * True iff any statement in the stream is rendered with
     * `transactionScope = STREAM_OWNED`.
     */
    fun streamOwnsTransaction(statements: List<MigrationDdlStatement>): Boolean =
        statements.any { it.transactionScope == TransactionScope.STREAM_OWNED }

    /**
     * Execute guard. Two ownership models can follow one another in one plan:
     * the runner's transaction and statements that must run outside it
     * (`NO_TRANSACTION`) — the executor splits them into consecutive segments.
     *
     * `STREAM_OWNED` cannot: such a stream carries its own `BEGIN`/`COMMIT`
     * markers, and a foreign segment beside them would sit inside or outside a
     * transaction the runner does not control.
     */
    fun unsupportedTransactionScopeReason(statements: List<MigrationDdlStatement>): String? {
        if (statements.isEmpty()) return null
        val scopes = statements.map { it.transactionScope }.toSet()
        return when {
            TransactionScope.STREAM_OWNED in scopes && scopes.size > 1 ->
                "mixed transaction scopes are not executable as one migration stream"
            scopes.singleOrNull() == TransactionScope.STREAM_OWNED &&
                statements.any { it.hints.transactionBehavior == TransactionBehavior.UNKNOWN } ->
                "stream-owned transaction boundaries are not fully described"
            else -> null
        }
    }
}
