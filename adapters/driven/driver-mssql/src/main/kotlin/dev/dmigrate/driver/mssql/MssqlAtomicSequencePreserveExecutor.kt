package dev.dmigrate.driver.mssql

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types

/**
 * Atomic-Preserve fuer SQL Server: Probe, geschuetzte Anweisungen und Restore
 * in **einer** Transaktion, je Sequenz unter einem benannten Lock.
 *
 * **`sys.sp_getapplock` ist das T-SQL-Gegenstueck zu PostgreSQLs
 * `pg_advisory_xact_lock`.** Mit `@LockOwner = 'Transaction'` haengt der Lock
 * an der Transaktion und faellt beim Commit oder Rollback von selbst weg — es
 * gibt nichts manuell freizugeben, und ein abgebrochener Lauf laesst keinen
 * Lock zurueck.
 *
 * **Der Rueckgabewert ist die Fehlerquelle, nicht die Exception.**
 * `sp_getapplock` wirft nicht, wenn es die Sperre nicht bekommt, sondern
 * liefert eine negative Zahl: `-1` Zeitueberschreitung, `-2` abgebrochen,
 * `-3` Deadlock-Opfer, `-999` Parameterfehler. Wer nur auf `SQLException`
 * hoert, haelt ein nicht erworbenes Lock fuer ein erworbenes und schreibt
 * ungeschuetzt weiter. `-1` und `-3` melden deshalb
 * [AtomicSequencePreserveResult.LockTimeout] — in beiden Faellen hat der
 * Aufrufer dieselbe Lage: die Sperre gehoert ihm nicht.
 *
 * **Die Transaktion wird ausdruecklich eroeffnet, nicht dem Treiber
 * ueberlassen.** Gemessen gegen SQL Server 2022: `autoCommit = false` allein
 * laesst `@@TRANCOUNT` bei **0** — auch nach einem ausgefuehrten Statement —,
 * und `sp_getapplock` mit `@LockOwner = 'Transaction'` antwortet dann mit
 * `-999` (Parameterfehler), nicht mit einer Sperre. Erst ein explizites
 * `BEGIN TRANSACTION` bringt `@@TRANCOUNT` auf 1 und die Sperre zustande.
 * Die naheliegende Ausweiche `@LockOwner = 'Session'` funktioniert zwar ohne
 * Transaktion, faellt aber beim Commit **nicht** von selbst weg — sie
 * muesste von Hand freigegeben werden, und ein abgebrochener Lauf liesse sie
 * stehen.
 *
 * **Die Sperrreihenfolge ist festgelegt** (Name, dann Schema), damit zwei
 * parallele Laeufe mit ueberlappenden Sequenzmengen sich serialisieren statt
 * ueber Kreuz zu warten.
 *
 * Der Executor besitzt Autocommit und Commit/Rollback der Verbindung; der
 * Aufrufer liefert eine Verbindung, die in keiner aeusseren Transaktion steht.
 */
class MssqlAtomicSequencePreserveExecutor : AtomicSequencePreserveExecutor {

    override fun execute(
        connection: DatabaseConnection,
        batch: AtomicSequencePreserveBatch,
        lockTimeoutMillis: Long,
        cancellationToken: CancellationToken,
        executeProtectedOperations: (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
    ): AtomicSequencePreserveResult {
        require(lockTimeoutMillis > 0) { "lockTimeoutMillis must be > 0, was $lockTimeoutMillis" }
        val sortedRequests = batch.requests.sortedWith(
            compareBy({ it.sequenceRef.name }, { it.sequenceRef.schema.orEmpty() }),
        )
        val sortedRefs = sortedRequests.map { it.sequenceRef }
        if (sortedRequests.isEmpty()) return AtomicSequencePreserveResult.Applied(emptyList())
        AtomicSequencePreserveExecutor.requireOwnedConnection(connection)
        if (cancellationToken.isCancellationRequested) {
            return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
        }

        val jdbc = connection.asJdbc()
        val previousAutoCommit = jdbc.autoCommit
        jdbc.autoCommit = false
        try {
            // Ohne diese Zeile ist `@@TRANCOUNT` null und `sp_getapplock`
            // antwortet mit -999 statt zu sperren (siehe KDoc oben).
            jdbc.createStatement().use { stmt -> stmt.execute("BEGIN TRANSACTION") }
            val probeResults = mutableMapOf<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>()
            for (request in sortedRequests) {
                lockAndProbe(jdbc, request.sequenceRef, lockTimeoutMillis, probeResults)
                    ?.let { earlyExit -> return earlyExit }
            }
            if (cancellationToken.isCancellationRequested) {
                runCatching { jdbc.rollback() }
                return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
            }
            runProtected(jdbc, batch.protectedOperationIds, executeProtectedOperations, sortedRefs)
                ?.let { earlyExit -> return earlyExit }
            if (cancellationToken.isCancellationRequested) {
                runCatching { jdbc.rollback() }
                return AtomicSequencePreserveResult.Cancelled(sortedRefs, cancellationToken.cancellationReason)
            }
            for (request in sortedRequests) {
                restore(jdbc, request, probeResults.getValue(request.sequenceRef))
                    ?.let { earlyExit -> return earlyExit }
            }
            jdbc.commit()
            return AtomicSequencePreserveResult.Applied(sortedRefs)
        } catch (e: Throwable) {
            runCatching { jdbc.rollback() }
            return AtomicSequencePreserveResult.Failed(sortedRefs.first(), e)
        } finally {
            // Der Lock haengt an der Transaktion und ist mit Commit/Rollback
            // weg; zurueckzusetzen ist nur der Autocommit, damit die
            // Verbindung so in den Pool zurueckgeht, wie sie kam.
            runCatching { jdbc.autoCommit = previousAutoCommit }
        }
    }

    /** Sperre holen und auf derselben Transaktion sondieren. */
    private fun lockAndProbe(
        connection: Connection,
        ref: SequenceObjectRef,
        lockTimeoutMillis: Long,
        probeResults: MutableMap<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>,
    ): AtomicSequencePreserveResult? {
        acquireLock(connection, ref, lockTimeoutMillis)?.let { return it }
        val probe = try {
            MssqlSequenceCurrentValueProbe.probe(connection, ref)
        } catch (e: Throwable) {
            connection.rollback()
            return AtomicSequencePreserveResult.Failed(ref, e)
        }
        return when (probe) {
            is SequenceCurrentValueProbeResult.Read -> {
                probeResults[ref] = probe
                null
            }

            is SequenceCurrentValueProbeResult.NotFound -> {
                connection.rollback()
                AtomicSequencePreserveResult.NotFound(listOf(ref))
            }

            is SequenceCurrentValueProbeResult.Failed -> {
                connection.rollback()
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("MSSQL probe failed: ${probe.code} ${probe.message}"),
                )
            }

            is SequenceCurrentValueProbeResult.NotApplicable -> {
                connection.rollback()
                AtomicSequencePreserveResult.Failed(
                    ref,
                    IllegalStateException("MSSQL probe returned NotApplicable for $ref — unreachable"),
                )
            }
        }
    }

    /**
     * `sp_getapplock` mit Ergebnisauswertung. `null` heisst: Sperre gehoert
     * uns; alles andere ist ein Abbruchgrund.
     */
    private fun acquireLock(
        connection: Connection,
        ref: SequenceObjectRef,
        lockTimeoutMillis: Long,
    ): AtomicSequencePreserveResult? {
        val status = try {
            connection.prepareCall(
                "{? = call sys.sp_getapplock(?, 'Exclusive', 'Transaction', ?)}",
            ).use { call ->
                call.registerOutParameter(1, Types.INTEGER)
                call.setString(2, lockKey(ref))
                // Das Budget ist millisekundengenau; SQL Server nimmt es als
                // INT und deckelt bei Int.MAX_VALUE.
                call.setInt(3, lockTimeoutMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                call.execute()
                call.getInt(1)
            }
        } catch (e: SQLException) {
            connection.rollback()
            return AtomicSequencePreserveResult.Failed(ref, e)
        }
        if (status >= 0) return null
        connection.rollback()
        return when (status) {
            LOCK_TIMEOUT, LOCK_DEADLOCK_VICTIM -> AtomicSequencePreserveResult.LockTimeout(listOf(ref))
            else -> AtomicSequencePreserveResult.Failed(
                ref,
                IllegalStateException("sp_getapplock returned $status for ${lockKey(ref)}"),
            )
        }
    }

    private fun runProtected(
        connection: Connection,
        protectedOperationIds: List<ProtectedOperationId>,
        executeProtectedOperations: (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
        sortedRefs: List<SequenceObjectRef>,
    ): AtomicSequencePreserveResult? = try {
        executeProtectedOperations(JdbcDatabaseConnection(connection), protectedOperationIds)
        null
    } catch (e: Throwable) {
        connection.rollback()
        AtomicSequencePreserveResult.Failed(sortedRefs.last(), e)
    }

    private fun restore(
        connection: Connection,
        request: AtomicSequencePreserveRequest,
        probe: SequenceCurrentValueProbeResult.Read,
    ): AtomicSequencePreserveResult? {
        val statements = try {
            request.renderRestore(probe)
        } catch (e: Throwable) {
            connection.rollback()
            return AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
        return try {
            connection.createStatement().use { stmt -> statements.forEach { stmt.execute(it) } }
            null
        } catch (e: SQLException) {
            connection.rollback()
            AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
    }

    /**
     * Der Sperrname. Das Praefix trennt unsere Sperren von denen jedes anderen
     * `sp_getapplock`-Nutzers derselben Datenbank; anders als bei PostgreSQL
     * ist der Schluessel eine Zeichenkette, es wird also nichts gehasht und
     * kann nichts kollidieren.
     */
    private fun lockKey(ref: SequenceObjectRef): String =
        "d-migrate:seq:${ref.schema.orEmpty()}.${ref.name}"

    companion object {
        /** `sp_getapplock`: Zeitbudget abgelaufen. */
        const val LOCK_TIMEOUT: Int = -1

        /** `sp_getapplock`: als Deadlock-Opfer zurueckgerollt. */
        const val LOCK_DEADLOCK_VICTIM: Int = -3
    }
}
