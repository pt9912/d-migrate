package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.connection.asJdbc

import com.mysql.cj.conf.PropertyKey
import com.mysql.cj.jdbc.JdbcConnection
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mysql.MySQLContainer


class MysqlDataWriterIntegrationTest : FunSpec({


    val container = MySQLContainer(TestImages.MYSQL)
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

        pool!!.borrow().asJdbc().use { conn ->
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
                // `created_at` ist bewusst dabei: MySQL setzt fuer eine Spalte mit
                // Default-*Ausdruck* EXTRA = DEFAULT_GENERATED -- eine Angabe, die
                // das Wort GENERATED traegt, ohne eine berechnete Spalte zu sein.
                stmt.execute(
                    "CREATE TABLE writer_generated (" +
                        "id INT NOT NULL PRIMARY KEY, " +
                        "qty INT NOT NULL, " +
                        "price INT NOT NULL, " +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "total_stored INT GENERATED ALWAYS AS (qty * price) STORED, " +
                        "total_virtual INT GENERATED ALWAYS AS (qty * price) VIRTUAL" +
                        ") ENGINE=InnoDB"
                )
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
        pool!!.borrow().asJdbc().use { conn ->
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
                stmt.execute("DELETE FROM writer_generated")
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
        pool!!.borrow().asJdbc().use { conn ->
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

    /**
     * MySQL lehnt jeden Wert fuer eine generierte Spalte ab ("The value
     * specified for generated column … is not allowed") -- erst beim Schreiben
     * des ersten Chunks. Gepruefte Zusicherung ist, dass `EXTRA` in
     * `information_schema.columns` **beide** Speicherformen findet und der
     * Import vorher mit Spaltennamen abbricht.
     */
    test("import into a generated column is refused by name, stored and virtual alike") {
        listOf("total_stored", "total_virtual").forEach { generated ->
            val session = writer.openTable(pool!!, "writer_generated", ImportOptions())
            val ex = session.use {
                shouldThrow<ImportSchemaMismatchException> {
                    it.write(
                        chunk(
                            table = "writer_generated",
                            columnNames = listOf("id", "qty", "price", generated),
                            rows = listOf(arrayOf<Any?>(1, 2, 3, 6)),
                        )
                    )
                }
            }
            ex.message shouldContain "computed column(s) $generated"
            ex.message shouldContain "MySQL does not allow writing them"
        }
    }

    /**
     * Die Gegenprobe zur Abgrenzung oben: `EXTRA = DEFAULT_GENERATED` (Spalte
     * mit Default-Ausdruck) ist **keine** berechnete Spalte, und ein Import,
     * der sie schreibt, ist gueltig. Ein `extra LIKE '%GENERATED%'` haette ihn
     * abgelehnt.
     */
    test("a DEFAULT_GENERATED column is writable and not taken for a computed one") {
        val extras = mutableMapOf<String, String>()
        pool!!.borrow().asJdbc().use { conn ->
            conn.prepareStatement(
                "SELECT column_name, extra FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'writer_generated'",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) extras[rs.getString(1)] = rs.getString(2)
                }
            }
        }
        // Gemessen, nicht angenommen: so nennt dieser Server die drei Faelle.
        extras["created_at"] shouldBe "DEFAULT_GENERATED"
        extras["total_stored"] shouldBe "STORED GENERATED"
        extras["total_virtual"] shouldBe "VIRTUAL GENERATED"

        writer.openTable(pool!!, "writer_generated", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_generated",
                    columnNames = listOf("id", "qty", "price", "created_at"),
                    rows = listOf(arrayOf<Any?>(9, 2, 3, java.sql.Timestamp.valueOf("2026-09-12 10:00:00"))),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
        }
    }

    test("the same table imports fine without the generated columns") {
        writer.openTable(pool!!, "writer_generated", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_generated",
                    columnNames = listOf("id", "qty", "price"),
                    rows = listOf(arrayOf<Any?>(1, 2, 3)),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
        }

        pool!!.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT total_stored, total_virtual FROM writer_generated").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 6
                    rs.getInt(2) shouldBe 6
                }
            }
        }
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

        pool!!.borrow().asJdbc().use { conn ->
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

        pool!!.borrow().asJdbc().use { conn ->
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

        pool!!.borrow().asJdbc().use { conn ->
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
        pool!!.borrow().asJdbc().use { conn ->
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
