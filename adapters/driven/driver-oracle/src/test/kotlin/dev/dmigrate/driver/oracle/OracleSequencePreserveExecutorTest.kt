package dev.dmigrate.driver.oracle

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
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
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * Das Oracle-Preserve-Fenster ohne Datenbank: Sperrreihenfolge, Freigabe und
 * vor allem die Faelle, in denen es **nicht** zusagt.
 *
 * Zwei davon sind Oracle-eigen und der Grund, warum dieser Executor nicht der
 * von PostgreSQL mit anderem SQL ist: die Sperre ist session-gebunden und muss
 * deshalb ausdruecklich freigegeben werden (sonst haelt sie bis zum Ende der
 * Verbindung), und `DBMS_LOCK` ist ein Recht, das der Migrationsnutzer
 * oft nicht hat — das gehoert benannt gemeldet, nicht stillschweigend
 * umgangen.
 */
class OracleSequencePreserveExecutorTest : FunSpec({

    fun ref(name: String, schema: String? = null) =
        SequenceObjectRef(name = name, schema = schema, dialect = RenameProjectionDialect.ORACLE)

    class FakeConnection(
        lockStatus: Int = 0,
        probeValue: Long? = 41L,
        lockFailure: SQLException? = null,
        restoreFailure: SQLException? = null,
    ) {
        val executed = mutableListOf<String>()
        val lockIds = mutableListOf<Int>()
        val releasedIds = mutableListOf<Int>()
        val lockTimeouts = mutableListOf<Int>()
        val connection: Connection = mockk(relaxed = true)

        init {
            every { connection.autoCommit } returns true

            every { connection.prepareCall(match { it.contains("dbms_lock.request") }) } answers {
                val call = mockk<CallableStatement>(relaxed = true)
                every { call.setInt(2, any()) } answers { lockIds += secondArg<Int>() }
                every { call.setInt(3, any()) } answers { lockTimeouts += secondArg<Int>() }
                every { call.execute() } answers { if (lockFailure != null) throw lockFailure else false }
                every { call.getInt(1) } returns lockStatus
                call
            }
            every { connection.prepareCall(match { it.contains("dbms_lock.release") }) } answers {
                val call = mockk<CallableStatement>(relaxed = true)
                every { call.setInt(2, any()) } answers { releasedIds += secondArg<Int>() }
                every { call.getInt(1) } returns 0
                call
            }

            // Die Probe laeuft ueber `prepareStatement`; jede bekommt ihr
            // eigenes ResultSet mit eigenem Cursor.
            every { connection.prepareStatement(any<String>()) } answers {
                val ps = mockk<PreparedStatement>(relaxed = true)
                val rows = mockk<ResultSet>(relaxed = true)
                var cursor = 0
                every { rows.next() } answers { probeValue != null && cursor++ == 0 }
                every { rows.getLong("last_number") } returns (probeValue ?: 0L)
                every { ps.executeQuery() } returns rows
                ps
            }

            val statement = mockk<Statement>(relaxed = true)
            every { statement.execute(any()) } answers {
                if (restoreFailure != null) throw restoreFailure
                executed += firstArg<String>()
                true
            }
            every { connection.createStatement() } returns statement
        }
    }

    fun batch(vararg refs: SequenceObjectRef) = AtomicSequencePreserveBatch(
        requests = refs.map { r -> AtomicSequencePreserveRequest(r) { listOf("ALTER SEQUENCE ${r.name} RESTART START WITH 42") } },
        protectedOperationIds = emptyList(),
        internalFollowUpIds = emptyList(),
    )

    fun run(
        fake: FakeConnection,
        batch: AtomicSequencePreserveBatch,
        cancellationToken: CancellationToken = CancellationToken.none(),
    ): AtomicSequencePreserveResult = OracleSequencePreserveExecutor().execute(
        connection = JdbcDatabaseConnection(fake.connection),
        batch = batch,
        lockTimeoutMillis = 5_000L,
        cancellationToken = cancellationToken,
        executeProtectedOperations = { _, _ -> AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0) },
    )

    test("the window applies, and every lock it took is given back") {
        // Session-gebundene Sperren fallen NICHT mit dem Commit weg. Wer sie
        // nicht freigibt, haelt sie bis zum Ende der Verbindung — und der
        // naechste Lauf wartet.
        val fake = FakeConnection()
        val result = run(fake, batch(ref("seq_a")))

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        fake.executed shouldContainExactly listOf("ALTER SEQUENCE seq_a RESTART START WITH 42")
        fake.releasedIds shouldContainExactly fake.lockIds
    }

    test("a missing DBMS_LOCK privilege is named, with the grant that fixes it") {
        // Der haeufigste Grund, warum dieser Pfad auf einer echten Anlage
        // nicht laeuft — und er ist behebbar, also gehoert er benannt.
        val fake = FakeConnection(lockFailure = SQLException("ORA-06550: PLS-00201: identifier 'DBMS_LOCK' must be declared"))
        val result = run(fake, batch(ref("seq_a")))

        val failed = result.shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        withClue(failed.cause.message.orEmpty()) {
            failed.cause.message!!.contains("GRANT EXECUTE ON DBMS_LOCK") shouldBe true
        }
        fake.executed.isEmpty() shouldBe true
    }

    test("a lock we did not get is a LockTimeout — and nothing is written") {
        val fake = FakeConnection(lockStatus = OracleSequencePreserveExecutor.STATUS_TIMEOUT)
        val result = run(fake, batch(ref("seq_a")))

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
        fake.executed.isEmpty() shouldBe true
        // Nicht erworben heisst auch: nichts freizugeben.
        fake.releasedIds.isEmpty() shouldBe true
    }

    test("the deadlock victim is the same situation as a timeout") {
        val fake = FakeConnection(lockStatus = OracleSequencePreserveExecutor.STATUS_DEADLOCK)
        run(fake, batch(ref("seq_a"))).shouldBeInstanceOf<AtomicSequencePreserveResult.LockTimeout>()
    }

    test("a lock this session already owns is no reason to stop") {
        // Status 4 heisst „du hast sie schon". Ihn als Fehler zu lesen
        // braeche einen Lauf, der bereits geschuetzt ist.
        val fake = FakeConnection(lockStatus = OracleSequencePreserveExecutor.STATUS_ALREADY_OWNED)
        run(fake, batch(ref("seq_a"))).shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        // Was wir nicht erworben haben, geben wir auch nicht frei.
        fake.releasedIds.isEmpty() shouldBe true
    }

    test("an unexpected status is a named failure, not a timeout") {
        val fake = FakeConnection(lockStatus = 3)
        val failed = run(fake, batch(ref("seq_a"))).shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        failed.cause.message!!.contains("returned 3") shouldBe true
    }

    test("locks are taken by name then schema, so parallel runs serialise") {
        val fake = FakeConnection()
        val executor = OracleSequencePreserveExecutor()
        run(fake, batch(ref("seq_b", "S2"), ref("seq_a", "S1"), ref("seq_a", "S0")))

        fake.lockIds shouldContainExactly listOf(
            executor.lockId(ref("seq_a", "S0")),
            executor.lockId(ref("seq_a", "S1")),
            executor.lockId(ref("seq_b", "S2")),
        )
    }

    test("the timeout budget is seconds, and never rounds down to none") {
        val fake = FakeConnection()
        OracleSequencePreserveExecutor().execute(
            connection = JdbcDatabaseConnection(fake.connection),
            batch = batch(ref("seq_a")),
            lockTimeoutMillis = 10L,
            executeProtectedOperations = { _, _ -> AtomicProtectedExecutionResult.Succeeded(0) },
        )
        // 10 ms sind unter einer Sekunde — abgerundet waere das Budget null,
        // und die Sperre schluege sofort fehl.
        fake.lockTimeouts shouldContainExactly listOf(1)
    }

    test("a sequence the database does not know stops the run, and releases what it held") {
        val fake = FakeConnection(probeValue = null)
        val result = run(fake, batch(ref("seq_gone")))

        result.shouldBeInstanceOf<AtomicSequencePreserveResult.NotFound>()
        fake.executed.isEmpty() shouldBe true
        fake.releasedIds shouldContainExactly fake.lockIds
    }

    test("a failing restore still gives the locks back") {
        // Ohne Ruecknahme (Oracle committet DDL) ist die Freigabe das
        // Einzige, was der Executor nach einem Fehlschlag noch richtig machen
        // kann.
        val fake = FakeConnection(restoreFailure = SQLException("ORA-01031"))
        run(fake, batch(ref("seq_a"))).shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        fake.releasedIds shouldContainExactly fake.lockIds
    }

    test("cancellation after the probe leaves the locks released") {
        val fake = FakeConnection()
        val source = CancellationTokenSource.create()
        source.cancel("Testabbruch")

        run(fake, batch(ref("seq_a")), cancellationToken = source.token)
            .shouldBeInstanceOf<AtomicSequencePreserveResult.Cancelled>()
        fake.releasedIds shouldContainExactly fake.lockIds
    }

    test("an empty batch never touches the connection") {
        val fake = FakeConnection()
        run(fake, batch()).shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        verify(exactly = 0) { fake.connection.prepareCall(any<String>()) }
    }

    test("a connection inside an enclosing transaction is refused") {
        val enclosed = mockk<Connection>(relaxed = true)
        every { enclosed.autoCommit } returns false
        val ex = shouldThrow<IllegalStateException> {
            OracleSequencePreserveExecutor().execute(
                connection = JdbcDatabaseConnection(enclosed),
                batch = batch(ref("seq_a")),
                lockTimeoutMillis = 5_000L,
                executeProtectedOperations = { _, _ -> AtomicProtectedExecutionResult.Succeeded(0) },
            )
        }
        ex.message!!.contains("requires an owned, non-enclosed connection") shouldBe true
    }

    test("the lock id stays inside the range DBMS_LOCK allows") {
        val executor = OracleSequencePreserveExecutor()
        val ids = listOf("a", "sehr_langer_sequenzname_mit_vielen_zeichen", "Z", "seq_1").map {
            executor.lockId(ref(it, "SCHEMA"))
        }
        withClue(ids.toString()) { ids.all { it in 0..1_073_741_823 } shouldBe true }
    }
})
