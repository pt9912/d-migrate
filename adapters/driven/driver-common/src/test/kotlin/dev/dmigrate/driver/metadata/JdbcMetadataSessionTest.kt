package dev.dmigrate.driver.metadata

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.sql.DriverManager

class JdbcMetadataSessionTest : FunSpec({

    fun withSession(block: (JdbcMetadataSession) -> Unit) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE test_table (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)")
                stmt.execute("INSERT INTO test_table (id, name, age) VALUES (1, 'Alice', 30)")
                stmt.execute("INSERT INTO test_table (id, name, age) VALUES (2, 'Bob', 25)")
            }
            block(JdbcMetadataSession(conn))
            // Verify connection is still open after session use
            conn.isClosed shouldBe false
        }
    }

    test("queryList returns all rows as maps") {
        withSession { session ->
            val rows = session.queryList("SELECT id, name, age FROM test_table ORDER BY id")
            rows shouldHaveSize 2
            rows[0]["id"] shouldBe 1
            rows[0]["name"] shouldBe "Alice"
            rows[0]["age"] shouldBe 30
            rows[1]["id"] shouldBe 2
            rows[1]["name"] shouldBe "Bob"
        }
    }

    test("queryList with parameters") {
        withSession { session ->
            val rows = session.queryList("SELECT name FROM test_table WHERE age > ?", 26)
            rows shouldHaveSize 1
            rows[0]["name"] shouldBe "Alice"
        }
    }

    test("queryList with no results returns empty list") {
        withSession { session ->
            val rows = session.queryList("SELECT * FROM test_table WHERE id = ?", 999)
            rows shouldHaveSize 0
        }
    }

    test("querySingle returns first row") {
        withSession { session ->
            val row = session.querySingle("SELECT name FROM test_table WHERE id = ?", 1)!!
            row.keys shouldBe setOf("name")
            row["name"] shouldBe "Alice"
        }
    }

    test("querySingle returns null for no results") {
        withSession { session ->
            val row = session.querySingle("SELECT * FROM test_table WHERE id = ?", 999)
            row.shouldBeNull()
        }
    }

    test("column names are lowercased") {
        withSession { session ->
            val rows = session.queryList("SELECT ID as MyId, NAME as FullName FROM test_table LIMIT 1")
            rows shouldHaveSize 1
            rows[0].keys shouldBe setOf("myid", "fullname")
        }
    }

    test("connection is not closed by session") {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (id INTEGER)") }
            val session = JdbcMetadataSession(conn)
            session.queryList("SELECT * FROM t")
            conn.isClosed shouldBe false
        }
    }
})
