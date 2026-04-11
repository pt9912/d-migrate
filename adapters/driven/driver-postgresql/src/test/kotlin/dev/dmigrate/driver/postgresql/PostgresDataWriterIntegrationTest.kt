package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer

private val WriterIntegrationTag = NamedTag("integration")

class PostgresDataWriterIntegrationTest : FunSpec({

    tags(WriterIntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())

        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            )
        )

        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE writer_users (id SERIAL PRIMARY KEY, name TEXT NOT NULL)")
                stmt.execute(
                    "CREATE TABLE writer_identity_always (" +
                        "id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, " +
                        "name TEXT NOT NULL)"
                )
                stmt.execute("CREATE TABLE writer_jsonb_target (id SERIAL PRIMARY KEY, payload JSONB)")
                stmt.execute("CREATE TABLE writer_interval_target (id SERIAL PRIMARY KEY, span INTERVAL)")
                stmt.execute("CREATE TABLE writer_zero_chunk (id SERIAL PRIMARY KEY, label TEXT)")
                stmt.execute("CREATE TABLE writer_upsert_target (id SERIAL PRIMARY KEY, name TEXT NOT NULL)")
                stmt.execute("CREATE TABLE writer_no_pk (name TEXT NOT NULL)")
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
                stmt.execute(
                    """
                    TRUNCATE TABLE
                        writer_users,
                        writer_identity_always,
                        writer_jsonb_target,
                        writer_interval_target,
                        writer_zero_chunk,
                        writer_upsert_target,
                        writer_no_pk
                    RESTART IDENTITY
                    """.trimIndent()
                )
            }
        }
    }

    fun chunk(
        table: String,
        columnNames: List<String>,
        rows: List<Array<Any?>>,
        chunkIndex: Long = 0,
    ): DataChunk = DataChunk(
        table = table,
        columns = columnNames.map { ColumnDescriptor(it, nullable = true) },
        rows = rows,
        chunkIndex = chunkIndex,
    )

    val writer = PostgresDataWriter()

    test("writes single chunk into target table") {
        writer.openTable(pool!!, "writer_users", ImportOptions()).use { session ->
            val result = session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(10L, "alice"), arrayOf<Any?>(11L, "bob")),
                )
            )
            result.rowsInserted shouldBe 2
            session.commitChunk()
            session.finishTable() shouldBe FinishTableResult.Success(
                listOf(
                    dev.dmigrate.driver.data.SequenceAdjustment(
                        table = "writer_users",
                        column = "id",
                        sequenceName = "public.writer_users_id_seq",
                        newValue = 12,
                    )
                )
            )
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id, name FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<Long, String>>()
                    while (rs.next()) {
                        rows += rs.getLong(1) to rs.getString(2)
                    }
                    rows shouldContainExactly listOf(10L to "alice", 11L to "bob")
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
                    rows = listOf(arrayOf<Any?>(1L, "a"), arrayOf<Any?>(2L, "b")),
                    chunkIndex = 0,
                )
            ).rowsInserted shouldBe 2
            session.commitChunk()

            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(3L, "c"), arrayOf<Any?>(4L, "d")),
                    chunkIndex = 1,
                )
            ).rowsInserted shouldBe 2
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val ids = mutableListOf<Long>()
                    while (rs.next()) {
                        ids += rs.getLong(1)
                    }
                    ids shouldContainExactly listOf(1L, 2L, 3L, 4L)
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
                    rows = listOf(arrayOf<Any?>(1L, "a"), arrayOf<Any?>(2L, "b")),
                )
            )
            session.commitChunk()

            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(3L, "c"), arrayOf<Any?>(4L, "d")),
                    chunkIndex = 1,
                )
            )
            session.rollbackChunk()

            session.write(
                chunk(
                    table = "writer_users",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(5L, "e")),
                    chunkIndex = 2,
                )
            )
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id FROM writer_users ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val ids = mutableListOf<Long>()
                    while (rs.next()) {
                        ids += rs.getLong(1)
                    }
                    ids shouldContainExactly listOf(1L, 2L, 5L)
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

    test("generated always uses overriding system value") {
        writer.openTable(pool!!, "writer_identity_always", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_identity_always",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(42L, "explicit-id")),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO writer_identity_always (name) VALUES ('next')")
            }
            conn.prepareStatement("SELECT id FROM writer_identity_always ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val ids = mutableListOf<Long>()
                    while (rs.next()) {
                        ids += rs.getLong(1)
                    }
                    ids shouldContainExactly listOf(42L, 43L)
                }
            }
        }
    }

    test("jsonb binding uses PostgreSQL specific path") {
        writer.openTable(pool!!, "writer_jsonb_target", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_jsonb_target",
                    columnNames = listOf("payload"),
                    rows = listOf(arrayOf<Any?>("""{"a":1,"nested":{"b":2}}""")),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT payload::text FROM writer_jsonb_target").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldContain "\"a\": 1"
                }
            }
        }
    }

    test("interval binding uses PostgreSQL specific path") {
        writer.openTable(pool!!, "writer_interval_target", ImportOptions()).use { session ->
            session.write(
                chunk(
                    table = "writer_interval_target",
                    columnNames = listOf("span"),
                    rows = listOf(arrayOf<Any?>("1 day 02:03:04")),
                )
            ).rowsInserted shouldBe 1
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT span::text FROM writer_interval_target").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1) shouldContain "1 day"
                }
            }
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
                        arrayOf<Any?>(1L, "updated"),
                        arrayOf<Any?>(2L, "inserted"),
                    ),
                )
            )
            result.rowsInserted shouldBe 1
            result.rowsUpdated shouldBe 1
            result.rowsSkipped shouldBe 0
            session.commitChunk()
            session.finishTable()
        }

        pool!!.borrow().use { conn ->
            conn.prepareStatement("SELECT id, name FROM writer_upsert_target ORDER BY id").use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<Long, String>>()
                    while (rs.next()) {
                        rows += rs.getLong(1) to rs.getString(2)
                    }
                    rows shouldContainExactly listOf(1L to "updated", 2L to "inserted")
                }
            }
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

        ex.message shouldContain "has no primary key"
    }
})
