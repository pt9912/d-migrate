package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Der Deadlock-Beweis fuer den SQL-Server-Executor — gegen einen echten
 * Server, weil nur er sagt, was `sp_getapplock` unter Kreuzung wirklich tut.
 *
 * Zwei Haelften, wie bei den drei aelteren Dialekten:
 *
 * - **Positiv:** zwei parallele Laeufe mit ueberlappenden Sequenzmengen
 *   (`[a, b]` und `[c, b]`) muessen sich serialisieren und beide `Applied`
 *   erreichen. Die namensortierte Reihenfolge des Executors schliesst das
 *   Diamant-Problem.
 * - **Negativ:** dieselben Sperren von Hand in **umgekehrter** Reihenfolge
 *   genommen. Das ist der Deadlock, den die Sortierung verhindert — und er
 *   belegt zugleich die Eigenart, um die es bei SQL Server geht:
 *   `sp_getapplock` **wirft nicht**, es liefert eine negative Zahl. Ein
 *   Executor, der nur auf Exceptions hoert, hielte hier ein nicht erworbenes
 *   Lock fuer ein erworbenes.
 */
class MssqlAtomicPreserveCrossPlanDeadlockTest : FunSpec({

    val container = startMssqlContainer()

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun openConn(): Connection =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun exec(sql: String) = openConn().use { c -> c.createStatement().use { it.execute(sql) } }

    fun currentValue(name: String): Long = openConn().use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT CAST(current_value AS BIGINT) FROM sys.sequences WHERE name = '$name'").use { rs ->
                check(rs.next()) { "sequence $name not found" }
                rs.getLong(1)
            }
        }
    }

    fun ref(name: String) = SequenceObjectRef(name, null, RenameProjectionDialect.MSSQL)

    val executor = MssqlAtomicSequencePreserveExecutor()
    val protectedOpId = ProtectedOperationId("AlterSequenceCurrentValue")

    test("two parallel preserve runs with overlapping sequences both commit") {
        listOf("xplan_a", "xplan_b", "xplan_c").forEach { name ->
            exec("IF OBJECT_ID('$name') IS NOT NULL DROP SEQUENCE $name")
            exec("CREATE SEQUENCE $name AS BIGINT START WITH 1 INCREMENT BY 1")
            exec("SELECT NEXT VALUE FOR $name")
        }

        fun batchFor(names: List<String>) = AtomicSequencePreserveBatch(
            requests = names.map { name ->
                AtomicSequencePreserveRequest(ref(name)) { probe ->
                    listOf("ALTER SEQUENCE $name RESTART WITH ${probe.value + 1}")
                }
            },
            protectedOperationIds = listOf(protectedOpId),
            internalFollowUpIds = listOf("op-xplan-${names.joinToString("-")}"),
        )

        // Plan 1 nennt [a, b], Plan 2 [c, b] — die Ueberlappung auf `b` ist
        // der Deadlock-Kandidat. Der Executor sortiert beide auf a → b → c.
        val plan1 = batchFor(listOf("xplan_a", "xplan_b"))
        val plan2 = batchFor(listOf("xplan_c", "xplan_b"))

        val plan1Started = CountDownLatch(1)
        val plan2Started = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val plan1Result = AtomicReference<AtomicSequencePreserveResult?>()
        val plan2Result = AtomicReference<AtomicSequencePreserveResult?>()
        try {
            val f1 = pool.submit<Unit> {
                openConn().use { c ->
                    plan1Started.countDown()
                    plan2Started.await(10, TimeUnit.SECONDS)
                    plan1Result.set(
                        executor.execute(JdbcDatabaseConnection(c), plan1, lockTimeoutMillis = 15_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            val f2 = pool.submit<Unit> {
                openConn().use { c ->
                    plan2Started.countDown()
                    plan1Started.await(10, TimeUnit.SECONDS)
                    plan2Result.set(
                        executor.execute(JdbcDatabaseConnection(c), plan2, lockTimeoutMillis = 15_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            f1.get(60, TimeUnit.SECONDS)
            f2.get(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        withClue("plan1=${plan1Result.get()} plan2=${plan2Result.get()}") {
            plan1Result.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            plan2Result.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        }
        // Und der Restore ist wirklich angekommen: `RESTART WITH` schreibt
        // `current_value` um, die Sequenz laeuft danach beim gesetzten Wert.
        currentValue("xplan_a") shouldBe 2L
    }

    test("crossed lock order deadlocks — and sp_getapplock says so with a number, not an exception") {
        val aFirst = CountDownLatch(1)
        val bFirst = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val aStatus = AtomicReference<Int?>()
        val bStatus = AtomicReference<Int?>()

        fun takeLock(c: Connection, key: String, timeoutMillis: Int): Int =
            c.prepareCall("{? = call sys.sp_getapplock(?, 'Exclusive', 'Transaction', ?)}").use { call ->
                call.registerOutParameter(1, Types.INTEGER)
                call.setString(2, key)
                call.setInt(3, timeoutMillis)
                call.execute()
                call.getInt(1)
            }

        try {
            val fA = pool.submit<Unit> {
                openConn().use { c ->
                    // Dieselbe Bedingung wie im Executor: ohne explizite
                    // Transaktion antwortet `sp_getapplock` mit -999 statt zu
                    // sperren (gemessen, siehe Executor-KDoc).
                    c.createStatement().use { it.execute("BEGIN TRANSACTION") }
                    takeLock(c, "d-migrate:seq:.seq_x", 2_000)
                    aFirst.countDown()
                    bFirst.await(10, TimeUnit.SECONDS)
                    aStatus.set(takeLock(c, "d-migrate:seq:.seq_y", 2_000))
                    runCatching { c.createStatement().use { s -> s.execute("ROLLBACK TRANSACTION") } }
                }
            }
            val fB = pool.submit<Unit> {
                openConn().use { c ->
                    c.createStatement().use { it.execute("BEGIN TRANSACTION") }
                    takeLock(c, "d-migrate:seq:.seq_y", 2_000)
                    bFirst.countDown()
                    aFirst.await(10, TimeUnit.SECONDS)
                    bStatus.set(takeLock(c, "d-migrate:seq:.seq_x", 2_000))
                    runCatching { c.createStatement().use { s -> s.execute("ROLLBACK TRANSACTION") } }
                }
            }
            fA.get(60, TimeUnit.SECONDS)
            fB.get(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        // Mindestens einer der beiden bekommt die Sperre NICHT — als
        // Rueckgabewert, nicht als Exception. `-1` ist die
        // Zeitueberschreitung, `-3` das Deadlock-Opfer; der Executor macht
        // aus beidem `LockTimeout`.
        val statuses = listOf(aStatus.get(), bStatus.get())
        withClue(statuses.toString()) {
            statuses.any {
                it == MssqlAtomicSequencePreserveExecutor.LOCK_TIMEOUT ||
                    it == MssqlAtomicSequencePreserveExecutor.LOCK_DEADLOCK_VICTIM
            } shouldBe true
        }
    }
})
