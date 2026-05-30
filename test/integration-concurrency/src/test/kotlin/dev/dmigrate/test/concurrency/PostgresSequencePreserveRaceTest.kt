package dev.dmigrate.test.concurrency

import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

private val ConcurrencyTag = NamedTag("concurrency")

/**
 * PostgreSQL reproducer for the SequencePreserveStage probe→restore
 * race window.
 *
 * Plan-Doc: `docs/planning/in-progress/quality-coverage-expansion-plan.md`
 * §5.3.
 *
 * **knownRace = true**: the assertion at the bottom of the spec
 * pins today's non-atomic stale-restore behaviour. The atomic-lock
 * slice will flip the assertion to `finalValue >= postWriterMaximum`.
 * Until then this spec is the canonical evidence that the race
 * exists and is reproducible.
 */
class PostgresSequencePreserveRaceTest : FunSpec({

    tags(ConcurrencyTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("concurrency_test")
        .withUsername("concurrency")
        .withPassword("concurrency")

    var jdbcUrl: String? = null
    var jdbcUser: String? = null
    var jdbcPassword: String? = null

    beforeSpec {
        container.start()
        jdbcUrl = container.jdbcUrl
        jdbcUser = container.username
        jdbcPassword = container.password
    }

    afterSpec {
        container.stop()
    }

    test("PG: nextval between probe and restore surfaces stale UPDATE (knownRace=true)") {
        val sequenceName = "order_seq"
        openConnection(jdbcUrl!!, jdbcUser!!, jdbcPassword!!).use { setup ->
            setup.autoCommit = true
            setup.createStatement().use { stmt ->
                stmt.execute("DROP SEQUENCE IF EXISTS $sequenceName")
                stmt.execute("CREATE SEQUENCE $sequenceName START WITH 1 INCREMENT BY 1")
                // Bring last_value to 1 by calling nextval once.
                stmt.execute("SELECT nextval('$sequenceName')")
            }
        }

        val observation = SequencePreserveRace.runAgainst(
            PostgresAdapter(jdbcUrl!!, jdbcUser!!, jdbcPassword!!, sequenceName),
        )

        // Legacy reproducer assertions — these will flip when the
        // atomic-lock slice lands. Each iteration uses a fresh
        // connection / autocommit, so visibility across threads is
        // identical to what SequencePreserveStage observes in
        // production today.
        withClue(observation) {
            observation.observedProbeValue shouldBe 1L
            observation.postWriterMaximum shouldBeGreaterThan observation.observedProbeValue
            // The stale-restore finding: setval snapped last_value
            // back to 1 even though the writer pushed it to 51.
            observation.finalValue shouldBe observation.observedProbeValue
            observation.knownRace shouldBe true
        }
    }
})

private inline fun withClue(observation: SequencePreserveRace.Observation, block: () -> Unit) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError(
            "race observation=$observation: ${t.message}",
            t,
        )
    }
}

private fun openConnection(url: String, user: String, password: String): Connection {
    Class.forName("org.postgresql.Driver")
    return DriverManager.getConnection(url, user, password).apply {
        autoCommit = true
    }
}

/**
 * PostgreSQL-specific adapter that drives [SequencePreserveRace.runAgainst].
 *
 * Each method opens a fresh autocommit connection so the probe,
 * writer and restore see each other's effects (the race is about
 * cross-transaction visibility, not snapshot isolation inside a
 * single transaction).
 */
private class PostgresAdapter(
    private val url: String,
    private val user: String,
    private val password: String,
    private val sequenceName: String,
) : SequencePreserveRace.Adapter {

    override fun readCurrentValue(): Long = openConnection(url, user, password).use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT last_value FROM $sequenceName").use { rs ->
                check(rs.next()) { "expected one row from SELECT last_value FROM $sequenceName" }
                rs.getLong(1)
            }
        }
    }

    override fun advance(): Long = openConnection(url, user, password).use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT nextval('$sequenceName')").use { rs ->
                check(rs.next()) { "expected one row from SELECT nextval" }
                rs.getLong(1)
            }
        }
    }

    override fun restore(value: Long) {
        openConnection(url, user, password).use { conn ->
            conn.createStatement().use { stmt ->
                // is_called = false so the next nextval() returns
                // `value` rather than value + 1 — matches what
                // PostgresSequenceCurrentValueRestore.kt emits.
                stmt.execute("SELECT setval('$sequenceName', $value, false)")
            }
        }
    }
}
