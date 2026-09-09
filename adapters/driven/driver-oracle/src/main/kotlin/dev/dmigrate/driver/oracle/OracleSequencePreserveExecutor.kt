package dev.dmigrate.driver.oracle

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
import kotlin.math.absoluteValue

/**
 * Das Preserve-Fenster fuer Oracle — **serialisiert, nicht atomar**.
 *
 * Der Unterschied ist Oracles, nicht unserer: dort committet **jedes** DDL
 * implizit, und sowohl die geschuetzten Operationen als auch der Restore
 * (`ALTER SEQUENCE … RESTART START WITH n`) sind DDL. Ein Fehlschlag in der
 * Mitte laesst also stehen, was bis dahin lief. Was das Fenster leisten kann,
 * ist das andere Halbe und das Wichtigere: **niemand sonst verbraucht
 * Sequenzwerte darin**.
 *
 * **Die Sperre ist deshalb session- und nicht transaktionsgebunden.** Live
 * gemessen gegen Oracle 23: mit `release_on_commit => TRUE` bekommt eine
 * zweite Sitzung die Sperre, sobald die erste ein DDL ausgefuehrt hat — das
 * implizite Commit gibt sie frei, mitten im Fenster. Mit
 * `release_on_commit => FALSE` haelt sie durch und wird hier ausdruecklich
 * wieder freigegeben.
 *
 * **`DBMS_LOCK` ist eine Eigenschaft der Verbindung, nicht des Dialekts.**
 * Das Paket gehoert SYS; das `EXECUTE`-Recht vergibt nur SYSDBA, und viele
 * Migrationsnutzer haben es nicht. Fehlt es, meldet der Executor das
 * **benannt** ([AtomicSequencePreserveResult.Failed] mit dem noetigen
 * `GRANT`) — kein stiller Rueckfall auf ein ungeschuetztes Fenster, denn der
 * saehe aus wie ein geschuetztes.
 *
 * Der Executor besitzt Commit und Rollback der Verbindung; der Aufrufer
 * liefert eine Verbindung, die in keiner aeusseren Transaktion steht.
 */
class OracleSequencePreserveExecutor : AtomicSequencePreserveExecutor {

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
        // Die Sperren, die wir wirklich halten — in Erwerbsreihenfolge, damit
        // die Freigabe sie alle erwischt, auch nach einem Abbruch in der
        // Mitte. Eine session-gebundene Sperre faellt sonst erst mit der
        // Verbindung weg.
        val held = mutableListOf<Int>()
        val window = Window(
            connection = jdbc,
            requests = sortedRequests,
            refs = sortedRefs,
            lockTimeoutMillis = lockTimeoutMillis,
            cancellationToken = cancellationToken,
            held = held,
            protectedOperationIds = batch.protectedOperationIds,
            executeProtectedOperations = executeProtectedOperations,
        )
        return try {
            runWindow(window)
        } catch (e: Throwable) {
            AtomicSequencePreserveResult.Failed(sortedRefs.first(), e)
        } finally {
            releaseAll(jdbc, held)
        }
    }

    /**
     * Alles, was ein Durchlauf braucht — als ein Gegenstand statt als acht
     * Parameter. Die Liste war ueber die Groessengrenze gewachsen; sie zu
     * unterdruecken haette den Schnitt nur verdeckt.
     */
    private class Window(
        val connection: Connection,
        val requests: List<AtomicSequencePreserveRequest>,
        val refs: List<SequenceObjectRef>,
        val lockTimeoutMillis: Long,
        val cancellationToken: CancellationToken,
        val held: MutableList<Int>,
        val protectedOperationIds: List<ProtectedOperationId>,
        val executeProtectedOperations:
            (DatabaseConnection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
    )

    private fun runWindow(window: Window): AtomicSequencePreserveResult {
        val probeResults = mutableMapOf<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>()
        for (request in window.requests) {
            lockAndProbe(window, request.sequenceRef, probeResults)?.let { return it }
        }
        window.cancelled()?.let { return it }
        try {
            window.executeProtectedOperations(
                JdbcDatabaseConnection(window.connection),
                window.protectedOperationIds,
            )
        } catch (e: Throwable) {
            return AtomicSequencePreserveResult.Failed(window.refs.last(), e)
        }
        window.cancelled()?.let { return it }
        for (request in window.requests) {
            restore(window.connection, request, probeResults.getValue(request.sequenceRef))?.let { return it }
        }
        return AtomicSequencePreserveResult.Applied(window.refs)
    }

    private fun Window.cancelled(): AtomicSequencePreserveResult? =
        if (cancellationToken.isCancellationRequested) {
            AtomicSequencePreserveResult.Cancelled(refs, cancellationToken.cancellationReason)
        } else {
            null
        }

    private fun lockAndProbe(
        window: Window,
        ref: SequenceObjectRef,
        probeResults: MutableMap<SequenceObjectRef, SequenceCurrentValueProbeResult.Read>,
    ): AtomicSequencePreserveResult? {
        val connection = window.connection
        acquireLock(connection, ref, window.lockTimeoutMillis, window.held)?.let { return it }
        return when (val probe = OracleSequenceCurrentValueProbe.probe(connection, ref)) {
            is SequenceCurrentValueProbeResult.Read -> {
                probeResults[ref] = probe
                null
            }

            is SequenceCurrentValueProbeResult.NotFound -> AtomicSequencePreserveResult.NotFound(listOf(ref))

            is SequenceCurrentValueProbeResult.Failed -> AtomicSequencePreserveResult.Failed(
                ref,
                IllegalStateException("Oracle probe failed: ${probe.code} ${probe.message}"),
            )

            is SequenceCurrentValueProbeResult.NotApplicable -> AtomicSequencePreserveResult.Failed(
                ref,
                IllegalStateException("Oracle probe returned NotApplicable for $ref — unreachable"),
            )
        }
    }

    /**
     * `DBMS_LOCK.REQUEST` mit **numerischer** Kennung. `ALLOCATE_UNIQUE`
     * bliebe die benannte Alternative, legt aber eine Zeile in
     * `DBMS_LOCK_ALLOCATED` an — ein dauerhaftes Objekt im Zielsystem fuer
     * eine Sperre, die Sekunden lebt.
     */
    private fun acquireLock(
        connection: Connection,
        ref: SequenceObjectRef,
        lockTimeoutMillis: Long,
        held: MutableList<Int>,
    ): AtomicSequencePreserveResult? {
        val id = lockId(ref)
        val status = try {
            connection.prepareCall("{? = call dbms_lock.request(?, dbms_lock.x_mode, ?, ?)}").use { call ->
                call.registerOutParameter(1, Types.INTEGER)
                call.setInt(2, id)
                // DBMS_LOCK rechnet in SEKUNDEN, nicht in Millisekunden; unter
                // einer Sekunde gaebe es sonst gar kein Budget.
                call.setInt(3, ceilToSeconds(lockTimeoutMillis))
                call.setBoolean(4, false)
                call.execute()
                call.getInt(1)
            }
        } catch (e: SQLException) {
            return AtomicSequencePreserveResult.Failed(ref, privilegeAwareCause(e))
        }
        if (status == STATUS_SUCCESS || status == STATUS_ALREADY_OWNED) {
            if (status == STATUS_SUCCESS) held += id
            return null
        }
        return when (status) {
            STATUS_TIMEOUT, STATUS_DEADLOCK -> AtomicSequencePreserveResult.LockTimeout(listOf(ref))
            else -> AtomicSequencePreserveResult.Failed(
                ref,
                IllegalStateException("dbms_lock.request returned $status for sequence '${ref.name}' (id $id)"),
            )
        }
    }

    /**
     * Der haeufigste Grund fuer ein Scheitern hier ist ein fehlendes Recht,
     * und der ist behebbar — also wird er benannt statt als „irgendein
     * SQL-Fehler" weitergereicht.
     */
    private fun privilegeAwareCause(e: SQLException): Throwable {
        val message = e.message.orEmpty()
        val looksLikeMissingGrant = ORA_IDENTIFIER_MUST_BE_DECLARED in message ||
            ORA_INSUFFICIENT_PRIVILEGES in message
        return if (looksLikeMissingGrant) {
            IllegalStateException(
                "The preserve window needs DBMS_LOCK, and this connection cannot use it: ${message.lines().first()}. " +
                    "DBMS_LOCK belongs to SYS; ask a SYSDBA for `GRANT EXECUTE ON DBMS_LOCK TO <user>`. " +
                    "Without it the sequence value cannot be preserved under a lock — and running without one " +
                    "would look protected while it is not.",
                e,
            )
        } else {
            e
        }
    }

    private fun restore(
        connection: Connection,
        request: AtomicSequencePreserveRequest,
        probe: SequenceCurrentValueProbeResult.Read,
    ): AtomicSequencePreserveResult? {
        val statements = try {
            restoreStatements(request, probe)
        } catch (e: Throwable) {
            return AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
        return try {
            connection.createStatement().use { stmt -> statements.forEach { stmt.execute(it) } }
            null
        } catch (e: SQLException) {
            AtomicSequencePreserveResult.Failed(request.sequenceRef, e)
        }
    }

    /**
     * Jede gehaltene Sperre wieder hergeben — auch wenn eine Freigabe
     * scheitert, damit ein Fehler die uebrigen nicht stehen laesst.
     */
    private fun releaseAll(connection: Connection, held: List<Int>) {
        for (id in held) {
            runCatching {
                connection.prepareCall("{? = call dbms_lock.release(?)}").use { call ->
                    call.registerOutParameter(1, Types.INTEGER)
                    call.setInt(2, id)
                    call.execute()
                }
            }
        }
    }

    /**
     * Die Sperrkennung aus dem Namen. Dasselbe Verfahren wie bei PostgreSQL
     * (dort `hashtext`): ein Hash, keine Registrierung. `DBMS_LOCK` laesst
     * Kennungen bis `1073741823` zu; der Rest des Bereichs gehoert Oracle.
     */
    internal fun lockId(ref: SequenceObjectRef): Int {
        val key = "d-migrate:seq:${ref.schema.orEmpty()}.${ref.name}"
        return (key.hashCode().toLong().absoluteValue % MAX_USER_LOCK_ID).toInt()
    }

    private fun ceilToSeconds(millis: Long): Int =
        ((millis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /**
     * `ALL_SEQUENCES.LAST_NUMBER` ist der **naechste** auszugebende Wert, nicht
     * der zuletzt ausgegebene (live gemessen, siehe [OracleSequenceResume]) —
     * anders als SQL Servers `current_value`. Er wird deshalb unveraendert
     * gesetzt; ihn zu versetzen verschenkte bei jedem Preserve einen Wert.
     */
    internal fun restoreStatements(
        request: AtomicSequencePreserveRequest,
        probe: SequenceCurrentValueProbeResult.Read,
    ): List<String> {
        val name = request.sequenceRef.name
        val definition = requireNotNull(request.sequence) {
            "Oracle preserve restore needs the sequence definition to compute the resume point " +
                "(sequence=$name): LAST_NUMBER means different things with and without CACHE."
        }
        val next = requireNotNull(OracleSequenceResume.resumePoint(probe.value, definition)) {
            "Sequence '$name' cannot resume after ${probe.value}: the next value lies outside " +
                "its MINVALUE/MAXVALUE range and it does not cycle."
        }
        return listOf(OracleSequenceDdl.restartSql(name, next))
    }

    companion object {
        /** `DBMS_LOCK.REQUEST`: Sperre erteilt. */
        const val STATUS_SUCCESS: Int = 0

        /** Zeitbudget abgelaufen. */
        const val STATUS_TIMEOUT: Int = 1

        /** Als Deadlock-Opfer abgewiesen. */
        const val STATUS_DEADLOCK: Int = 2

        /** Diese Sitzung haelt die Sperre bereits — kein Grund abzubrechen. */
        const val STATUS_ALREADY_OWNED: Int = 4

        private const val MAX_USER_LOCK_ID = 1_073_741_823L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val ORA_IDENTIFIER_MUST_BE_DECLARED = "PLS-00201"
        private const val ORA_INSUFFICIENT_PRIVILEGES = "ORA-01031"
    }
}
