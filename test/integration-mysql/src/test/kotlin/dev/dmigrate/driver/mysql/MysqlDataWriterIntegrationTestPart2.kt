package dev.dmigrate.driver.mysql

import com.mysql.cj.conf.PropertyKey
import com.mysql.cj.jdbc.JdbcConnection
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer

private val MysqlWriterIntegrationTag = NamedTag("integration")

class MysqlDataWriterIntegrationTestPart2 : FunSpec({

    tags(MysqlWriterIntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MysqlDriver())

        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                pool = PoolSettings(maximumPoolSize = 1, minimumIdle = 1),
            )
        )

        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE writer_users (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100) NOT NULL" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE writer_zero_chunk (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "label VARCHAR(100)" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE writer_upsert_target (" +
                        "id INT AUTO_INCREMENT PRIMARY KEY, " +
                        "name VARCHAR(100) NOT NULL" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE writer_unique_target (" +
                        "id INT NOT NULL PRIMARY KEY, " +
                        "email VARCHAR(200) NOT NULL UNIQUE, " +
                        "name VARCHAR(100) NOT NULL" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute("CREATE TABLE writer_no_pk (name VARCHAR(100) NOT NULL) ENGINE=InnoDB")
                stmt.execute(
                    "CREATE TABLE writer_composite_target (" +
                        "z_part INT NOT NULL, " +
                        "a_part INT NOT NULL, " +
                        "name VARCHAR(100) NOT NULL, " +
                        "PRIMARY KEY (z_part, a_part)" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE `WriterCaseTarget` (" +
                        "id INT NOT NULL PRIMARY KEY, " +
                        "name VARCHAR(100) NOT NULL" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE writer_parent (" +
                        "id INT NOT NULL PRIMARY KEY" +
                        ") ENGINE=InnoDB"
                )
                stmt.execute(
                    "CREATE TABLE writer_child (" +
                        "id INT NOT NULL PRIMARY KEY, " +
                        "parent_id INT NOT NULL, " +
                        "CONSTRAINT fk_writer_child_parent FOREIGN KEY (parent_id) REFERENCES writer_parent(id)" +
                        ") ENGINE=InnoDB"
                )
            }
        }
    }

    afterSpec {
        pool?.close()
        if (container.isRunning) container.stop()
        DatabaseDriverRegistry.clear()
    }

    beforeTest {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("SET FOREIGN_KEY_CHECKS = 0")
                stmt.execute("DELETE FROM writer_users")
                stmt.execute("ALTER TABLE writer_users AUTO_INCREMENT = 1")
                stmt.execute("DELETE FROM writer_zero_chunk")
                stmt.execute("ALTER TABLE writer_zero_chunk AUTO_INCREMENT = 1")
                stmt.execute("DELETE FROM writer_upsert_target")
                stmt.execute("ALTER TABLE writer_upsert_target AUTO_INCREMENT = 1")
                stmt.execute("DELETE FROM writer_unique_target")
                stmt.execute("DELETE FROM writer_no_pk")
                stmt.execute("DELETE FROM writer_composite_target")
                stmt.execute("DELETE FROM `WriterCaseTarget`")
                stmt.execute("DELETE FROM writer_child")
                stmt.execute("DELETE FROM writer_parent")
                stmt.execute("SET FOREIGN_KEY_CHECKS = 1")
            }
        }
    }

    fun chunk(
        table: String,
        columnNames: List<String>,
        rows: List<Array<Any?>>,
        chunkIndex: Long = 0,
    ) = DataChunk(
        table = table,
        columns = columnNames.map { ColumnDescriptor(it, nullable = true) },
        rows = rows,
        chunkIndex = chunkIndex,
    )

    fun lowerCaseTableNames(): Int =
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@lower_case_table_names").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1)
                }
            }
        }

    val writer = MysqlDataWriter()


    test("onConflict skip reports inserted vs skipped") {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO writer_upsert_target (id, name) VALUES (1, 'existing')")
            }
        }

        writer.openTable(
            pool!!,
            "writer_upsert_target",
            ImportOptions(onConflict = OnConflict.SKIP),
        ).use { session ->
            val result = session.write(
                chunk(
                    table = "writer_upsert_target",
                    columnNames = listOf("id", "name"),
                    rows = listOf(
                        arrayOf<Any?>(1, "ignored"),
                        arrayOf<Any?>(2, "inserted"),
                    ),
                )
            )
            result.rowsInserted shouldBe 1
            result.rowsSkipped shouldBe 1
            session.commitChunk()
            session.finishTable()
        }
    }

    test("disable fk checks allows importing child rows before parent rows") {
        shouldThrow<Exception> {
            writer.openTable(pool!!, "writer_child", ImportOptions()).use { session ->
                session.write(
                    chunk(
                        table = "writer_child",
                        columnNames = listOf("id", "parent_id"),
                        rows = listOf(arrayOf<Any?>(1, 10)),
                    )
                )
                session.commitChunk()
            }
        }

        writer.openTable(
            pool!!,
            "writer_child",
            ImportOptions(disableFkChecks = true),
        ).use { session ->
            session.write(
                chunk(
                    table = "writer_child",
                    columnNames = listOf("id", "parent_id"),
                    rows = listOf(arrayOf<Any?>(1, 10)),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
            session.finishTable()
        }

        writer.openTable(pool!!, "writer_parent", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_parent",
                    columnNames = listOf("id"),
                    rows = listOf(arrayOf<Any?>(10)),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
            session.finishTable()
        }
    }

    test("disable fk checks is reset before pooled connection is reused") {
        writer.openTable(
            pool!!,
            "writer_child",
            ImportOptions(disableFkChecks = true),
        ).use { session ->
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT @@FOREIGN_KEY_CHECKS").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    test("onConflict update counts updates for conflicts on secondary unique keys") {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO writer_unique_target (id, email, name) VALUES (1, 'a@example.com', 'old')"
                )
            }
        }

        writer.openTable(
            pool!!,
            "writer_unique_target",
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            val result = session.write(
                chunk(
                    table = "writer_unique_target",
                    columnNames = listOf("id", "email", "name"),
                    rows = listOf(arrayOf<Any?>(2, "a@example.com", "updated")),
                )
            )
            result.rowsInserted shouldBe 0
            result.rowsUpdated shouldBe 1
            result.rowsUnknown shouldBe 0
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id, email, name FROM writer_unique_target").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getInt("id") shouldBe 1
                    rs.getString("email") shouldBe "a@example.com"
                    rs.getString("name") shouldBe "updated"
                }
            }
        }
    }

    test("rewriteBatchedStatements is enabled for writer connection") {
        pool!!.borrow().use { conn ->
            val unwrapped = conn.unwrap(JdbcConnection::class.java)
            unwrapped.propertySet
                .getBooleanProperty(PropertyKey.rewriteBatchedStatements)
                .value shouldBe true
        }
    }

    test("onConflict update supports composite primary keys in key sequence order") {
        writer.openTable(
            pool!!,
            "writer_composite_target",
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            session.write(
                chunk(
                    table = "writer_composite_target",
                    columnNames = listOf("z_part", "a_part", "name"),
                    rows = listOf(
                        arrayOf<Any?>(2, 1, "first"),
                        arrayOf<Any?>(2, 2, "second"),
                    ),
                )
            )
            session.commitChunk()
            session.finishTable()
        }

        writer.openTable(
            pool!!,
            "writer_composite_target",
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            val result = session.write(
                chunk(
                    table = "writer_composite_target",
                    columnNames = listOf("z_part", "a_part", "name"),
                    rows = listOf(
                        arrayOf<Any?>(2, 1, "updated"),
                        arrayOf<Any?>(3, 1, "inserted"),
                    ),
                )
            )
            result.rowsInserted shouldBe 1
            result.rowsUpdated shouldBe 1
            session.commitChunk()
            session.finishTable()
        }
    }

    test("truncate deletes pre-existing rows before import") {
        // Seed pre-existing data
        pool!!.borrow().use { conn ->
            conn.prepareStatement("INSERT INTO writer_users (id, name) VALUES (?, ?)").use { ps ->
                ps.setInt(1, 99)
                ps.setString(2, "pre-existing")
                ps.executeUpdate()
            }
        }

        writer.openTable(pool!!, "writer_users", ImportOptions(truncate = true)).use { session ->
            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(1, "alice")),
                )
            )
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id, name FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<Int, String>>()
                    while (rs.next()) {
                        rows += rs.getInt(1) to rs.getString(2)
                    }
                    rows shouldContainExactly listOf(1 to "alice")
                }
            }
        }
    }

    test("primary key lookup respects lower_case_table_names normalization") {
        val tableName = if (lowerCaseTableNames() == 0) "WriterCaseTarget" else "writercasetarget"

        writer.openTable(
            pool!!,
            tableName,
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            session.write(
                chunk(
                    table = tableName,
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(1, "first")),
                )
            )
            session.commitChunk()
            session.finishTable()
        }

        writer.openTable(
            pool!!,
            tableName,
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            val result = session.write(
                chunk(
                    table = tableName,
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(1, "updated")),
                )
            )
            result.rowsUpdated shouldBe 1
            session.commitChunk()
            session.finishTable()
        }
    }
})
