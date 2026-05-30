package dev.dmigrate.test.concurrency

import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.Connection
import java.sql.DriverManager

private val ConcurrencyTag = NamedTag("concurrency")

/**
 * MySQL reproducer for the SequencePreserveStage probe→restore race
 * window. MySQL has no native sequences; d-migrate emulates them
 * via a `dmg_sequences` helper table. The race exists in exactly
 * the same shape: probe reads `next_value`, writer increments it
 * concurrently, restore overwrites the writer's progress.
 *
 * Plan-Doc: `docs/planning/in-progress/quality-coverage-expansion-plan.md`
 * §5.3.
 *
 * **knownRace = true**: legacy reproducer. Flip after the
 * atomic-lock slice lands.
 */
class MysqlSequencePreserveRaceTest : FunSpec({

    tags(ConcurrencyTag)

    val container = MySQLContainer("mysql:8.0")
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

    test("MySQL: nextval between probe and restore surfaces stale UPDATE (knownRace=true)") {
        val sequenceName = "order_seq"
        openConnection(jdbcUrl!!, jdbcUser!!, jdbcPassword!!).use { setup ->
            setup.autoCommit = true
            setup.createStatement().use { stmt ->
                // Matches the canonical d-migrate dmg_sequences shape
                // (subset sufficient for the race observation).
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS dmg_sequences (
                        name VARCHAR(64) PRIMARY KEY,
                        next_value BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    """.trimIndent()
                )
                stmt.execute("DELETE FROM dmg_sequences WHERE name = '$sequenceName'")
                stmt.execute("INSERT INTO dmg_sequences (name, next_value) VALUES ('$sequenceName', 1)")
            }
        }

        val observation = SequencePreserveRace.runAgainst(
            MysqlAdapter(jdbcUrl!!, jdbcUser!!, jdbcPassword!!, sequenceName),
        )

        withClue(observation) {
            observation.observedProbeValue shouldBe 1L
            observation.postWriterMaximum shouldBeGreaterThan observation.observedProbeValue
            observation.finalValue shouldBe observation.observedProbeValue
            observation.knownRace shouldBe true
        }
    }
})

private inline fun withClue(observation: SequencePreserveRace.Observation, block: () -> Unit) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError("race observation=$observation: ${t.message}", t)
    }
}

private fun openConnection(url: String, user: String, password: String): Connection {
    Class.forName("com.mysql.cj.jdbc.Driver")
    return DriverManager.getConnection(url, user, password).apply { autoCommit = true }
}

private class MysqlAdapter(
    private val url: String,
    private val user: String,
    private val password: String,
    private val sequenceName: String,
) : SequencePreserveRace.Adapter {

    override fun readCurrentValue(): Long = openConnection(url, user, password).use { conn ->
        conn.prepareStatement("SELECT next_value FROM dmg_sequences WHERE name = ?").use { stmt ->
            stmt.setString(1, sequenceName)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "expected one row for sequence $sequenceName" }
                rs.getLong(1)
            }
        }
    }

    override fun advance(): Long = openConnection(url, user, password).use { conn ->
        // Two-step: UPDATE then read. A single SELECT…FOR UPDATE
        // would mask the race because we want the writer to see the
        // post-update visibility, mirroring what dmg_nextval does in
        // production. The race exists at the cross-transaction
        // visibility level which both approaches reproduce.
        conn.prepareStatement(
            "UPDATE dmg_sequences SET next_value = next_value + 1 WHERE name = ?"
        ).use { stmt ->
            stmt.setString(1, sequenceName)
            stmt.executeUpdate()
        }
        conn.prepareStatement("SELECT next_value FROM dmg_sequences WHERE name = ?").use { stmt ->
            stmt.setString(1, sequenceName)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "expected one row after UPDATE" }
                rs.getLong(1)
            }
        }
    }

    override fun restore(value: Long) = openConnection(url, user, password).use { conn ->
        conn.prepareStatement("UPDATE dmg_sequences SET next_value = ? WHERE name = ?").use { stmt ->
            stmt.setLong(1, value)
            stmt.setString(2, sequenceName)
            stmt.executeUpdate()
        }
        Unit
    }
}
