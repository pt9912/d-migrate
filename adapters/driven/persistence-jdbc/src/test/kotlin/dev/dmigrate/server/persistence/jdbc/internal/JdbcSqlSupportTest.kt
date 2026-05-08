package dev.dmigrate.server.persistence.jdbc.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

class JdbcSqlSupportTest : FunSpec({

    fun freshDb(block: (Connection) -> Unit) {
        val tmp = kotlin.io.path.createTempFile(prefix = "sql-support-", suffix = ".db").toFile()
        tmp.deleteOnExit()
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${tmp.absolutePath}" }
        ds.connection.use(block)
    }

    test("bindAll dispatches String/Int/Long/Boolean/Instant/null/Object") {
        freshDb { conn ->
            conn.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE t (
                      s TEXT, i INTEGER, l INTEGER, b INTEGER,
                      ts TEXT, n TEXT, o TEXT
                    )
                    """.trimIndent(),
                )
            }
            val now = Instant.parse("2026-05-06T10:00:00Z")
            conn.executeUpdate(
                "INSERT INTO t (s, i, l, b, ts, n, o) VALUES (?, ?, ?, ?, ?, ?, ?)",
                "txt", 42, 9_000_000_000L, true, now, null, java.math.BigDecimal("3.14"),
            ) shouldBe 1

            val row = conn.querySingle("SELECT s, i, l, b, ts, n, o FROM t") { rs ->
                listOf(
                    rs.getString("s"),
                    rs.getInt("i"),
                    rs.getLong("l"),
                    rs.getBoolean("b"),
                    rs.getString("ts"),
                    rs.getString("n"),
                    rs.getString("o"),
                )
            }
            row!![0] shouldBe "txt"
            row[1] shouldBe 42
            row[2] shouldBe 9_000_000_000L
            row[3] shouldBe true
            // SQLite stores TIMESTAMP as INTEGER/REAL via xerial-jdbc; the
            // exact textual rendering varies per driver. Verify only that
            // the bound value round-trips non-null. Instant-typed
            // round-trips are covered separately (getInstant test below).
            (row[4] != null) shouldBe true
            row[5] shouldBe null
            // BigDecimal -> setObject -> SQLite stores as text/numeric
            (row[6].toString().contains("3.14")) shouldBe true
        }
    }

    test("getInstantOrNull returns null for missing TIMESTAMP, value otherwise") {
        freshDb { conn ->
            conn.createStatement().use {
                it.execute("CREATE TABLE t (a TIMESTAMP, b TIMESTAMP)")
            }
            val now = Instant.parse("2026-05-06T10:00:00Z")
            conn.prepareStatement("INSERT INTO t (a, b) VALUES (?, NULL)").use { ps ->
                ps.setTimestamp(1, Timestamp.from(now))
                ps.executeUpdate()
            }
            conn.prepareStatement("SELECT a, b FROM t").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getInstant("a") shouldBe now
                    rs.getInstantOrNull("b").shouldBeNull()
                }
            }
        }
    }

    test("getInstant errors when the column is null") {
        freshDb { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (a TIMESTAMP)") }
            conn.executeUpdate("INSERT INTO t (a) VALUES (NULL)")
            shouldThrow<IllegalStateException> {
                conn.querySingle("SELECT a FROM t") { rs -> rs.getInstant("a") }
            }
        }
    }

    test("querySingle returns null for empty result; executes maps for single row") {
        freshDb { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (n INTEGER)") }
            conn.querySingle("SELECT n FROM t") { it.getInt("n") }.shouldBeNull()
            conn.executeUpdate("INSERT INTO t (n) VALUES (?)", 7) shouldBe 1
            conn.querySingle("SELECT n FROM t") { it.getInt("n") } shouldBe 7
        }
    }
})
