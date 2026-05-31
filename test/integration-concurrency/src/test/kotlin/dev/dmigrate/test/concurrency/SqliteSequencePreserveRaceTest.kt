package dev.dmigrate.test.concurrency

import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

private val ConcurrencyTag = NamedTag("concurrency")

/**
 * SQLite reproducer for the SequencePreserveStage probe→restore
 * race window.
 *
 * SQLite serialises writes within a single connection, so this
 * reproducer uses a real **file-backed** database and opens a fresh
 * connection per operation. Each connection sees the others'
 * committed effects via the file-system back-end — exactly what
 * SQLite emulation (`dmg_sequences` + helper triggers, see
 * `docs/planning/done/sqlite-sequence-emulation-plan.md`) faces in
 * production.
 *
 * Plan-Doc: `docs/planning/done/quality-coverage-expansion-plan.md`
 * §5.3.
 *
 * **knownRace = true**: legacy reproducer. Flip after the
 * atomic-lock slice lands.
 */
class SqliteSequencePreserveRaceTest : FunSpec({

    tags(ConcurrencyTag)

    val sequenceName = "order_seq"
    val tmpDbFile: Path = Files.createTempFile("dmigrate-concurrency-sqlite-", ".db")

    beforeSpec {
        tmpDbFile.deleteIfExists()
        Files.createFile(tmpDbFile)
        val url = jdbcUrl(tmpDbFile)
        openConnection(url).use { setup ->
            setup.autoCommit = true
            setup.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS dmg_sequences (
                        name TEXT PRIMARY KEY,
                        next_value INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                stmt.execute("DELETE FROM dmg_sequences WHERE name = '$sequenceName'")
                stmt.execute("INSERT INTO dmg_sequences (name, next_value) VALUES ('$sequenceName', 1)")
            }
        }
    }

    afterSpec {
        tmpDbFile.deleteIfExists()
    }

    test("SQLite: nextval between probe and restore surfaces stale UPDATE (knownRace=true)") {
        val observation = SequencePreserveRace.runAgainst(
            SqliteAdapter(jdbcUrl(tmpDbFile), sequenceName),
        )

        withClue(observation) {
            observation.observedProbeValue shouldBe 1L
            observation.postWriterMaximum shouldBeGreaterThan observation.observedProbeValue
            observation.finalValue shouldBe observation.observedProbeValue
            observation.knownRace shouldBe true
        }
    }
})

private fun jdbcUrl(path: Path): String = "jdbc:sqlite:${path.absolutePathString()}"

private inline fun withClue(observation: SequencePreserveRace.Observation, block: () -> Unit) {
    try {
        block()
    } catch (t: AssertionError) {
        throw AssertionError("race observation=$observation: ${t.message}", t)
    }
}

private fun openConnection(url: String): Connection {
    Class.forName("org.sqlite.JDBC")
    return DriverManager.getConnection(url).apply { autoCommit = true }
}

private class SqliteAdapter(
    private val url: String,
    private val sequenceName: String,
) : SequencePreserveRace.Adapter {

    override fun readCurrentValue(): Long = openConnection(url).use { conn ->
        conn.prepareStatement("SELECT next_value FROM dmg_sequences WHERE name = ?").use { stmt ->
            stmt.setString(1, sequenceName)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "expected one row for sequence $sequenceName" }
                rs.getLong(1)
            }
        }
    }

    override fun advance(): Long = openConnection(url).use { conn ->
        // SQLite's xerial driver applies setQueryTimeout as a lock-
        // wait timeout (see memory: Driver-Timeout-Quirks). The
        // file-DB + per-op connection pattern avoids the SQLite
        // single-writer serialisation issue and gives the writer
        // thread a chance to run between the probe's read and the
        // probe's restore.
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

    override fun restore(value: Long) = openConnection(url).use { conn ->
        conn.prepareStatement("UPDATE dmg_sequences SET next_value = ? WHERE name = ?").use { stmt ->
            stmt.setLong(1, value)
            stmt.setString(2, sequenceName)
            stmt.executeUpdate()
        }
        Unit
    }
}
