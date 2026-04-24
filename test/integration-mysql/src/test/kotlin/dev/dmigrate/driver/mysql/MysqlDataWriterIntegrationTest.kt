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

class MysqlDataWriterIntegrationTest : FunSpec({

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
                params = mapOf("allowPublicKeyRetrieval" to "true"),
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

    test("schemaSync exposes MysqlSchemaSync") {
        writer.schemaSync().javaClass shouldBe MysqlSchemaSync::class.java
    }

    test("writes single chunk into target table") {
        writer.openTable(pool!!, "writer_users", ImportOptions()).use { session ->
            val result = session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(10, "alice"), arrayOf<Any?>(11, "bob")),
                )
            )
            result.rowsInserted shouldBe 2
            session.commitChunk()
            session.finishTable() shouldBe FinishTableResult.Success(
                listOf(
                    dev.dmigrate.driver.data.SequenceAdjustment(
                        table = "writer_users",
                        column = "id",
                        sequenceName = null,
                        newValue = 12,
                    )
                )
            )
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id, name FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<Int, String>>()
                    while (rs.next()) {
                        rows += rs.getInt(1) to rs.getString(2)
                    }
                    rows shouldContainExactly listOf(10 to "alice", 11 to "bob")
                }
            }
        }
    }

    test("writes multiple chunks with commit boundaries") {
        writer.openTable(pool!!, "writer_users", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(1, "a"), arrayOf<Any?>(2, "b")),
                )
            ).rowsInserted shouldBe 2
            session.commitChunk()

            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(3, "c"), arrayOf<Any?>(4, "d")),
                    chunkIndex = 1,
                )
            ).rowsInserted shouldBe 2
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val ids = mutableListOf<Int>()
                    while (rs.next()) {
                        ids += rs.getInt(1)
                    }
                    ids shouldContainExactly listOf(1, 2, 3, 4)
                }
            }
        }
    }

    test("rollbackChunk discards current chunk only") {
        writer.openTable(pool!!, "writer_users", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(1, "a"), arrayOf<Any?>(2, "b")),
                )
            )
            session.commitChunk()

            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(3, "c"), arrayOf<Any?>(4, "d")),
                    chunkIndex = 1,
                )
            )
            session.rollbackChunk()

            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(5, "e")),
                    chunkIndex = 2,
                )
            )
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val ids = mutableListOf<Int>()
                    while (rs.next()) {
                        ids += rs.getInt(1)
                    }
                    ids shouldContainExactly listOf(1, 2, 5)
                }
            }
        }
    }

    test("finishTable from OPEN supports zero chunk path") {
        writer.openTable(pool!!, "writer_zero_chunk", ImportOptions()).use { session ->
            session.finishTable() shouldBe FinishTableResult.Success(emptyList())
        }

        pool!!.activeConnections() shouldBe 0
    }

    test("triggerMode disable is rejected for MySQL") {
        shouldThrow<IllegalStateException> {
            writer.openTable(
                pool!!,
                "writer_users",
                ImportOptions(triggerMode = dev.dmigrate.driver.data.TriggerMode.DISABLE),
            ).close()
        }
    }

    test("triggerMode strict is rejected for MySQL") {
        shouldThrow<IllegalStateException> {
            writer.openTable(
                pool!!,
                "writer_users",
                ImportOptions(triggerMode = dev.dmigrate.driver.data.TriggerMode.STRICT),
            ).close()
        }
    }

    test("onConflict update upserts rows and reports inserted vs updated") {
        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO writer_upsert_target (id, name) VALUES (1, 'old')")
            }
        }

        writer.openTable(
            pool!!,
            "writer_upsert_target",
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            val result = session.write(
                chunk(
                    table = "writer_upsert_target",
                    columnNames = listOf("id", "name"),
                    rows = listOf(
                        arrayOf<Any?>(1, "updated"),
                        arrayOf<Any?>(2, "inserted"),
                    ),
                )
            )
            result.rowsInserted shouldBe 1
            result.rowsUpdated shouldBe 1
            result.rowsSkipped shouldBe 0
            result.rowsUnknown shouldBe 0
            session.commitChunk()
            session.finishTable()
        }
    }

    test("onConflict update rejects target tables without primary key") {
        val ex = shouldThrow<IllegalArgumentException> {
            writer.openTable(
                pool!!,
                "writer_no_pk",
                ImportOptions(onConflict = OnConflict.UPDATE),
            ).close()
        }

        ex.message shouldBe "Target table 'writer_no_pk' has no primary key; onConflict=update requires a primary key"
    }

})
