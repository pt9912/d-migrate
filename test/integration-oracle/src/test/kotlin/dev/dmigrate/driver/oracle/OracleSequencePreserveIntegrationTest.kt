package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
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
import java.sql.DriverManager
import java.time.Duration

/**
 * Das Preserve-Fenster auf Oracle, live abgenommen — **und der Unterschied,
 * den es zu den vier anderen hat.**
 *
 * Oracles Fenster ist `SERIALIZED`, nicht `ATOMIC`: niemand kommt dazwischen,
 * aber ein Fehlschlag laesst stehen, was bis dahin lief. Das ist keine Luecke
 * im Bau, sondern eine Eigenschaft des Servers — jedes DDL committet dort
 * implizit, und sowohl die geschuetzten Operationen als auch der Restore sind
 * DDL. Bisher stand diese Zusage nur im Text; die letzte Spec hier misst sie.
 *
 * **Voraussetzung, die keine der anderen vier hat:** `DBMS_LOCK` gehoert SYS,
 * und das `EXECUTE`-Recht vergibt nur ein Administrator. Fehlt es, faellt der
 * Executor nicht still auf ein ungeschuetztes Fenster zurueck, sondern meldet
 * es benannt — auch das steht hier als Messung, nicht als Behauptung.
 */
class OracleSequencePreserveIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE).withStartupTimeout(Duration.ofMinutes(5))
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        // `DBMS_LOCK` gehoert SYS, und das EXECUTE-Recht vergibt nur ein
        // Administrator. Der Container liefert einen Anwendungsnutzer ohne
        // dieses Recht — gemessen: ohne den GRANT meldet der Executor benannt
        // `Failed` samt der noetigen Anweisung, statt still ungeschuetzt
        // weiterzumachen. Genau diese Anweisung fuehrt der Test hier aus.
        // `system` reicht dafuer nicht (ORA-01031, gemessen): ein Recht auf ein
        // SYS-Paket vergibt nur SYS selbst.
        val asSysdba = java.util.Properties().apply {
            setProperty("user", "sys")
            setProperty("password", container.password)
            setProperty("internal_logon", "sysdba")
        }
        DriverManager.getConnection(container.jdbcUrl, asSysdba).use { admin ->
            admin.createStatement().use {
                it.execute("GRANT EXECUTE ON SYS.DBMS_LOCK TO ${container.username}")
            }
        }
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host, port = container.oraclePort,
                database = container.databaseName,
                user = container.username, password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(sql: String) = pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute(sql) } }

    fun nextVal(name: String): Long = pool.borrow().asJdbc().use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT $name.NEXTVAL FROM dual").use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun ref(name: String) = SequenceObjectRef(name.uppercase(), null, RenameProjectionDialect.ORACLE)

    val executor = OracleSequencePreserveExecutor()
    val protectedOp = ProtectedOperationId("AlterSequenceCurrentValue")
    val definition = SequenceDefinition(start = 100L, increment = 1L, preserveCurrentValue = true)

    // ── Die Probe ──────────────────────────────────────────────────────

    test("the probe reads LAST_NUMBER, which is the allocation ceiling — not the last value handed out") {
        // NOCACHE, damit die Messung nicht vom Vorrat abhaengt: dann ist
        // `last_number` genau der naechste auszugebende Wert.
        exec("CREATE SEQUENCE preserve_probe START WITH 100 INCREMENT BY 1 NOCACHE")

        val fresh = pool.borrow().asJdbc().use { OracleSequenceCurrentValueProbe.probe(it, ref("preserve_probe")) }
        withClue("noch nichts gezogen") {
            fresh.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>().value shouldBe 100L
        }

        nextVal("preserve_probe") shouldBe 100L

        val used = pool.borrow().asJdbc().use { OracleSequenceCurrentValueProbe.probe(it, ref("preserve_probe")) }
        withClue("nach dem ersten Zug steht der naechste auszugebende Wert dort") {
            used.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>().value shouldBe 101L
        }
    }

    test("a sequence that is not there is NotFound, not an error") {
        val result = pool.borrow().asJdbc().use {
            OracleSequenceCurrentValueProbe.probe(it, ref("preserve_absent"))
        }

        (result is SequenceCurrentValueProbeResult.NotFound) shouldBe true
    }

    // ── Der Executor ───────────────────────────────────────────────────

    test("Applied: probe, protected statements and restore run inside one window") {
        exec("CREATE SEQUENCE preserve_one START WITH 100 INCREMENT BY 1 NOCACHE")
        nextVal("preserve_one") shouldBe 100L
        nextVal("preserve_one") shouldBe 101L

        val batch = AtomicSequencePreserveBatch(
            requests = listOf(AtomicSequencePreserveRequest(ref("preserve_one"), definition)),
            protectedOperationIds = listOf(protectedOp),
            internalFollowUpIds = listOf("op-preserve_one"),
        )

        val result = pool.borrow().asJdbc().use { c ->
            executor.execute(JdbcDatabaseConnection(c), batch, lockTimeoutMillis = 5_000) { conn, ops ->
                ops shouldBe listOf(protectedOp)
                conn.asJdbc().createStatement().use { s ->
                    s.executeQuery("SELECT preserve_one.NEXTVAL FROM dual").use { rs -> rs.next() }
                }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 1)
            }
        }

        withClue(result.toString()) {
            result.shouldBeInstanceOf<AtomicSequencePreserveResult.Applied>().refs shouldBe listOf(ref("preserve_one"))
        }
        withClue("der vorgefundene Stand ist wiederhergestellt, nicht der, den die geschuetzte Anweisung hinterliess") {
            nextVal("preserve_one") shouldBe 102L
        }
    }

    test("NotFound: a missing sequence ends the window instead of guessing") {
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(AtomicSequencePreserveRequest(ref("preserve_absent"), definition)),
            protectedOperationIds = listOf(protectedOp),
            internalFollowUpIds = listOf("op-absent"),
        )

        val result = pool.borrow().asJdbc().use { c ->
            executor.execute(JdbcDatabaseConnection(c), batch, lockTimeoutMillis = 5_000) { _, _ ->
                error("die geschuetzte Anweisung darf gar nicht laufen")
            }
        }

        (result is AtomicSequencePreserveResult.NotFound) shouldBe true
    }

    // ── Der Unterschied: SERIALIZED ist nicht ATOMIC ───────────────────

    test("a failure inside the window leaves what already ran — this is what SERIALIZED means") {
        exec("CREATE SEQUENCE preserve_partial START WITH 100 INCREMENT BY 1 NOCACHE")
        exec("CREATE TABLE preserve_witness (marker NUMBER)")
        nextVal("preserve_partial") shouldBe 100L

        val batch = AtomicSequencePreserveBatch(
            requests = listOf(AtomicSequencePreserveRequest(ref("preserve_partial"), definition)),
            protectedOperationIds = listOf(protectedOp),
            internalFollowUpIds = listOf("op-partial"),
        )

        val result = pool.borrow().asJdbc().use { c ->
            executor.execute(JdbcDatabaseConnection(c), batch, lockTimeoutMillis = 5_000) { conn, _ ->
                // Erst ein DDL, das gelingt — Oracle committet es implizit.
                conn.asJdbc().createStatement().use { s ->
                    s.execute("ALTER TABLE preserve_witness ADD (applied_before_failure NUMBER)")
                }
                // Dann eines, das der Server ablehnt: dieselbe Spalte ein
                // zweites Mal. Ein gezielter Fehlschlag, kein Zufall — der
                // Fehlschlag reist als Ausnahme, so verlangt es der Port.
                conn.asJdbc().createStatement().use { s ->
                    s.execute("ALTER TABLE preserve_witness ADD (applied_before_failure NUMBER)")
                }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 2)
            }
        }

        withClue(result.toString()) {
            (result is AtomicSequencePreserveResult.Applied) shouldBe false
        }
        // DAS ist der gemessene Unterschied zu einem atomaren Fenster: die
        // erste Anweisung steht, obwohl die zweite scheiterte.
        val columnStands = pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT COUNT(*) FROM user_tab_columns " +
                        "WHERE table_name = 'PRESERVE_WITNESS' AND column_name = 'APPLIED_BEFORE_FAILURE'",
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        withClue("ein atomares Fenster haette die erste Anweisung zurueckgenommen") { columnStands shouldBe 1 }
    }
})
