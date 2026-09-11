package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.oracle.OracleContainer
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Das Oracle-Preserve-Fenster gegen einen echten Server.
 *
 * Zwei Faelle, und der erste ist der haeufigere: **ohne das `EXECUTE`-Recht
 * auf `DBMS_LOCK`** kann der Pfad nicht laufen. Das Paket gehoert SYS, das
 * Recht vergibt nur SYSDBA, und ein Migrationsnutzer hat es meist nicht. Der
 * Executor sagt dann, was fehlt — statt ohne Sperre weiterzumachen, was wie
 * ein geschuetzter Lauf aussaehe.
 *
 * Der zweite Fall belegt die Rechnung, an der ein Fehler erst auffiele, wenn
 * Schluessel kollidieren: `LAST_NUMBER` ist bei `NOCACHE` der zuletzt
 * **ausgegebene** Wert. Wer dort fortsetzt, gibt ihn ein zweites Mal aus.
 */
class OracleSequencePreserveWindowIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE)
        .withStartupTimeout(Duration.ofMinutes(5))

    beforeSpec { container.start() }
    afterSpec { container.stop() }

    fun openConn(): Connection =
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    fun sysdbaConn(): Connection = DriverManager.getConnection(
        container.jdbcUrl,
        Properties().apply {
            this["user"] = "sys"
            this["password"] = container.password
            this["internal_logon"] = "sysdba"
        },
    )

    fun exec(sql: String) = openConn().use { c -> c.createStatement().use { it.execute(sql) } }

    fun nextVal(name: String): Long = openConn().use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT $name.NEXTVAL FROM dual").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun ref(name: String) = SequenceObjectRef(name.uppercase(), null, RenameProjectionDialect.ORACLE)

    val executor = OracleSequencePreserveExecutor()

    /**
     * Die Sequenz, wie die Tests sie anlegen: aufsteigend, Schrittweite 1,
     * ohne erklaerte Schranken.
     */
    val createdSequence = SequenceDefinition(start = 1L, increment = 1L)

    fun batchFor(names: List<String>) = AtomicSequencePreserveBatch(
        requests = names.map { name ->
            AtomicSequencePreserveRequest(ref(name), createdSequence)
        },
        protectedOperationIds = listOf(ProtectedOperationId("AlterSequenceCurrentValue")),
        internalFollowUpIds = listOf("op-${names.joinToString("-")}"),
    )

    test("without the DBMS_LOCK grant the run says what is missing, and writes nothing") {
        exec("CREATE SEQUENCE nogrant_seq START WITH 1 INCREMENT BY 1 NOCACHE")
        val before = nextVal("nogrant_seq")

        val result = openConn().use { c ->
            executor.execute(JdbcDatabaseConnection(c), batchFor(listOf("nogrant_seq")), lockTimeoutMillis = 5_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
        }

        val failed = result.shouldBeInstanceOf<AtomicSequencePreserveResult.Failed>()
        withClue(failed.cause.message.orEmpty()) {
            failed.cause.message!!.contains("GRANT EXECUTE ON DBMS_LOCK") shouldBe true
        }
        // Und die Sequenz ist unberuehrt: der naechste Wert folgt dem vorigen.
        nextVal("nogrant_seq") shouldBe before + 1
    }

    test("with the grant the window holds — and the sequence continues without repeat or gap") {
        sysdbaConn().use { sys ->
            sys.createStatement().use { it.execute("GRANT EXECUTE ON DBMS_LOCK TO ${container.username}") }
        }
        exec("CREATE SEQUENCE preserve_seq START WITH 1 INCREMENT BY 1 NOCACHE")
        val issued = nextVal("preserve_seq")

        // Der Restore rendert der Treiber selbst — derselbe Weg, den der
        // Produktivpfad geht.
        val result = openConn().use { c ->
            executor.execute(JdbcDatabaseConnection(c), batchFor(listOf("preserve_seq")), lockTimeoutMillis = 5_000) { _, _ ->
                AtomicProtectedExecutionResult.Succeeded(0)
            }
        }
        result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()

        // Der eigentliche Beweis, und er muss scharf sein: „groesser als
        // vorher" liesse jede zu grosse Zahl durch und faende einen
        // verschobenen Fortsetzungspunkt nie. Nach dem Preserve muss der
        // naechste Wert LUECKENLOS folgen — weder wiederholt noch
        // uebersprungen.
        val after = nextVal("preserve_seq")
        withClue("ausgegeben=$issued danach=$after") { after shouldBe issued + 1 }
    }

    test("two parallel windows over overlapping sequences both apply") {
        listOf("xp_a", "xp_b", "xp_c").forEach { name ->
            exec("CREATE SEQUENCE $name START WITH 1 INCREMENT BY 1 NOCACHE")
            nextVal(name)
        }

        val plan1 = batchFor(listOf("xp_a", "xp_b"))
        val plan2 = batchFor(listOf("xp_c", "xp_b"))
        val started1 = CountDownLatch(1)
        val started2 = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val r1 = AtomicReference<AtomicSequencePreserveResult?>()
        val r2 = AtomicReference<AtomicSequencePreserveResult?>()
        try {
            val f1 = pool.submit<Unit> {
                openConn().use { c ->
                    started1.countDown()
                    started2.await(20, TimeUnit.SECONDS)
                    r1.set(
                        executor.execute(JdbcDatabaseConnection(c), plan1, lockTimeoutMillis = 20_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            val f2 = pool.submit<Unit> {
                openConn().use { c ->
                    started2.countDown()
                    started1.await(20, TimeUnit.SECONDS)
                    r2.set(
                        executor.execute(JdbcDatabaseConnection(c), plan2, lockTimeoutMillis = 20_000) { _, _ ->
                            AtomicProtectedExecutionResult.Succeeded(0)
                        },
                    )
                }
            }
            f1.get(90, TimeUnit.SECONDS)
            f2.get(90, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }

        withClue("plan1=${r1.get()} plan2=${r2.get()}") {
            r1.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
            r2.get().shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>()
        }
    }
})
