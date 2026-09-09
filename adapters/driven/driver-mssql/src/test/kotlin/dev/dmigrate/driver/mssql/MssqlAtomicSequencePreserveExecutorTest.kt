package dev.dmigrate.driver.mssql

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * Was der SQL-Server-Executor tut, **bevor** eine echte Datenbank im Spiel
 * ist: Reihenfolge, Abbruchwege und vor allem die Auswertung von
 * `sp_getapplock`.
 *
 * Der Rueckgabewert ist hier die Fehlerquelle, nicht die Exception —
 * `sp_getapplock` wirft nicht, wenn es die Sperre nicht bekommt, sondern
 * liefert eine negative Zahl. Ein Executor, der nur auf `SQLException`
 * hoert, haelt ein nicht erworbenes Lock fuer ein erworbenes und schreibt
 * ungeschuetzt weiter. Genau das pruefen die Faelle unten.
 */
class MssqlAtomicSequencePreserveExecutorTest : FunSpec({

    fun ref(name: String, schema: String? = null) =
        SequenceObjectRef(name = name, schema = schema, dialect = RenameProjectionDialect.MSSQL)

    /**
     * Eine Verbindung, die auf `sp_getapplock` mit [lockStatus] antwortet und
     * fuer die Probe [probeValue] liefert (`null` = keine Zeile).
     */
    class FakeConnection(lockStatus: Int, probeValue: Long? = 42L, restoreFailure: SQLException? = null) {
        /** Alles, was ueber `Statement.execute` lief — samt Transaktionsklammer. */
        val executed = mutableListOf<String>()

        /**
         * Nur die Restore-Anweisungen. Die Klammer (`BEGIN TRANSACTION`) ist
         * kein Schreibvorgang an der Sequenz; sie hier auszunehmen haelt die
         * Zusicherung „nichts geschrieben" bei dem, was sie meint.
         */
        val restores: List<String> get() = executed.filterNot { it == "BEGIN TRANSACTION" }
        var committed = false
        var rolledBack = false
        val lockKeys = mutableListOf<String>()
        val lockTimeouts = mutableListOf<Int>()

        val connection: Connection = mockk(relaxed = true)

        init {
            every { connection.autoCommit } returns true
            every { connection.commit() } answers { committed = true }
            every { connection.rollback() } answers { rolledBack = true }

            val call = mockk<CallableStatement>(relaxed = true)
            every { call.setString(2, any()) } answers { lockKeys += secondArg<String>() }
            every { call.setInt(3, any()) } answers { lockTimeouts += secondArg<Int>() }
            every { call.getInt(1) } returns lockStatus
            every { connection.prepareCall(any()) } returns call

            // Jede Probe braucht ihr EIGENES `ResultSet` mit eigenem
            // Cursor: mehrere Sequenzen im selben Batch loesen mehrere
            // `executeQuery`-Aufrufe aus, und ein geteiltes Mock-`ResultSet`
            // liesse die zweite Probe den bereits verbrauchten Cursor der
            // ersten sehen — sie faende dann faelschlich keine Zeile.
            val statement = mockk<Statement>(relaxed = true)
            every { statement.executeQuery(any()) } answers {
                val rows = mockk<ResultSet>(relaxed = true)
                var cursor = 0
                every { rows.next() } answers { probeValue != null && cursor++ == 0 }
                every { rows.getLong("cv") } returns (probeValue ?: 0L)
                rows
            }
            // Die Fehlerbahn direkt hier verdrahten statt spaeter im Testfall
            // erneut zu stubben: ein `every {...}` auf einem bereits
            // gestubbten Aufrufpfad ist eine zweite Fehlerquelle, die mit dem
            // eigentlichen Verhalten des Executors nichts zu tun hat.
            every { statement.execute(any()) } answers {
                if (restoreFailure != null) throw restoreFailure
                executed += firstArg<String>()
                true
            }
            every { connection.createStatement() } returns statement
        }
    }

    fun batch(vararg refs: SequenceObjectRef, sequence: SequenceDefinition? = SequenceDefinition()) =
        AtomicSequencePreserveBatch(
            requests = refs.map { r -> AtomicSequencePreserveRequest(r, sequence) },
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )

    fun run(
        fake: FakeConnection,
        batch: AtomicSequencePreserveBatch,
        cancellationToken: CancellationToken = CancellationToken.none(),
        protectedOperations: () -> AtomicProtectedExecutionResult = {
            AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
        },
    ): AtomicSequencePreserveResult = MssqlAtomicSequencePreserveExecutor().execute(
        connection = JdbcDatabaseConnection(fake.connection),
        batch = batch,
        lockTimeoutMillis = 5_000L,
        cancellationToken = cancellationToken,
        executeProtectedOperations = { _, _ -> protectedOperations() },
    )

    test("a lock that was not granted is a LockTimeout, not a silent write") {
        // -1 ist SQL Servers Zeitueberschreitung. Wer sie uebersieht, schreibt
        // ohne Sperre weiter — und genau davon lebt der Atomic-Pfad.
        val fake = FakeConnection(lockStatus = MssqlAtomicSequencePreserveExecutor.LOCK_TIMEOUT)
        val result = run(fake, batch(ref("seq_a")))

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
        fake.rolledBack shouldBe true
        fake.committed shouldBe false
        withClue(fake.executed.toString()) { fake.restores.isEmpty() shouldBe true }
    }

    test("the deadlock victim is the same situation as a timeout — the lock is not ours") {
        val fake = FakeConnection(lockStatus = MssqlAtomicSequencePreserveExecutor.LOCK_DEADLOCK_VICTIM)
        run(fake, batch(ref("seq_a"))).shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
        fake.rolledBack shouldBe true
    }

    test("an unexpected status is a named failure, not a timeout") {
        // -999 heisst Parameterfehler. Ihn als Zeitueberschreitung zu melden
        // legte dem Anwender einen Wiederholungsversuch nahe, der nie hilft.
        val fake = FakeConnection(lockStatus = -999)
        val result = run(fake, batch(ref("seq_a")))

        val failed = result.shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        failed.cause.message!!.contains("-999") shouldBe true
        fake.rolledBack shouldBe true
    }

    test("granted lock, probe, protected operations and restore commit together") {
        val fake = FakeConnection(lockStatus = 0)
        val result = run(fake, batch(ref("seq_a")))

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        fake.committed shouldBe true
        fake.rolledBack shouldBe false
        // `current_value` ist der zuletzt ausgegebene Wert, `RESTART WITH`
        // setzt den naechsten: der geprobte 42 plus Schrittweite 1.
        fake.restores shouldContainExactly listOf("ALTER SEQUENCE [seq_a] RESTART WITH 43;")
    }

    test("the lock order is by name then schema, so parallel runs serialise instead of crossing") {
        val fake = FakeConnection(lockStatus = 0)
        run(fake, batch(ref("seq_b", "s2"), ref("seq_a", "s1"), ref("seq_a", "s0")))

        fake.lockKeys shouldContainExactly listOf(
            "d-migrate:seq:s0.seq_a",
            "d-migrate:seq:s1.seq_a",
            "d-migrate:seq:s2.seq_b",
        )
    }

    test("a sequence the server does not know stops the run before anything is written") {
        val fake = FakeConnection(lockStatus = 0, probeValue = null)
        val result = run(fake, batch(ref("seq_gone")))

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
        fake.rolledBack shouldBe true
        fake.restores.isEmpty() shouldBe true
    }

    test("a restore that fails rolls the whole transaction back") {
        val fake = FakeConnection(lockStatus = 0, restoreFailure = SQLException("boom"))

        run(fake, batch(ref("seq_a"))).shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        fake.rolledBack shouldBe true
        fake.committed shouldBe false
    }

    test("cancellation after the probe leaves nothing behind") {
        val fake = FakeConnection(lockStatus = 0)
        val source = CancellationTokenSource.create()
        source.cancel("Testabbruch")

        val result = run(fake, batch(ref("seq_a")), cancellationToken = source.token)

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
        fake.committed shouldBe false
    }

    test("the transaction is opened explicitly — sp_getapplock refuses without one") {
        // Gemessen gegen SQL Server 2022: `autoCommit = false` allein laesst
        // `@@TRANCOUNT` bei 0, und `sp_getapplock` antwortet dann mit -999
        // statt zu sperren. Die Klammer ist damit Teil des Vertrags, nicht
        // Beiwerk.
        val fake = FakeConnection(lockStatus = 0)
        run(fake, batch(ref("seq_a")))

        fake.executed.first() shouldBe "BEGIN TRANSACTION"
    }

    test("an empty batch never touches the connection") {
        val fake = FakeConnection(lockStatus = 0)
        val result = run(fake, batch())

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        verify(exactly = 0) { fake.connection.prepareCall(any()) }
    }

    test("a connection inside an enclosing transaction is refused") {
        val enclosed = mockk<Connection>(relaxed = true)
        every { enclosed.autoCommit } returns false
        val ex = shouldThrow<IllegalStateException> {
            MssqlAtomicSequencePreserveExecutor().execute(
                connection = JdbcDatabaseConnection(enclosed),
                batch = batch(ref("seq_a")),
                lockTimeoutMillis = 5_000L,
                executeProtectedOperations = { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
                },
            )
        }
        ex.message!!.contains("requires an owned, non-enclosed connection") shouldBe true
    }
})
