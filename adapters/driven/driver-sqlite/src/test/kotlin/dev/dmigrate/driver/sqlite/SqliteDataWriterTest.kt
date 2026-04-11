package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists

class SqliteDataWriterTest : FunSpec({

    lateinit var dbFile: Path
    lateinit var pool: ConnectionPool

    beforeEach {
        dbFile = Files.createTempFile("d-migrate-sqlite-writer-", ".db")
        dbFile.deleteIfExists()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.SQLITE,
                host = null,
                port = null,
                database = dbFile.absolutePathString(),
                user = null,
                password = null,
            )
        )

        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE writer_users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL)"
                )
                stmt.execute(
                    "CREATE TABLE writer_zero_chunk (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "label TEXT)"
                )
                stmt.execute(
                    "CREATE TABLE writer_upsert_target (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL)"
                )
                stmt.execute("CREATE TABLE writer_no_pk (name TEXT NOT NULL)")
                stmt.execute(
                    "CREATE TABLE writer_composite_target (" +
                        "z_part INTEGER NOT NULL, " +
                        "a_part INTEGER NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "PRIMARY KEY (z_part, a_part))"
                )
                stmt.execute(
                    "CREATE TABLE \"MixedCaseTarget\" (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL)"
                )
                stmt.execute("CREATE TABLE writer_parent (id INTEGER NOT NULL PRIMARY KEY)")
                stmt.execute(
                    "CREATE TABLE writer_child (" +
                        "id INTEGER NOT NULL PRIMARY KEY, " +
                        "parent_id INTEGER NOT NULL, " +
                        "FOREIGN KEY(parent_id) REFERENCES writer_parent(id))"
                )
            }
        }
    }

    afterEach {
        pool.close()
        dbFile.deleteIfExists()
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

    val writer = SqliteDataWriter()

    test("writes single chunk into target table") {
        writer.openTable(pool, "writer_users", ImportOptions()).use { session ->
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

        pool.borrow().use { conn ->
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
        writer.openTable(pool, "writer_users", ImportOptions()).use { session ->
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

        pool.borrow().use { conn ->
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
        writer.openTable(pool, "writer_users", ImportOptions()).use { session ->
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

        pool.borrow().use { conn ->
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
        writer.openTable(pool, "writer_zero_chunk", ImportOptions()).use { session ->
            session.finishTable() shouldBe FinishTableResult.Success(emptyList())
        }
    }

    test("onConflict update upserts rows and reports inserted vs updated") {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO writer_upsert_target (id, name) VALUES (1, 'old')")
            }
        }

        writer.openTable(
            pool,
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
            session.commitChunk()
            session.finishTable()
        }
    }

    test("onConflict update rejects target tables without primary key") {
        val ex = shouldThrow<IllegalArgumentException> {
            writer.openTable(
                pool,
                "writer_no_pk",
                ImportOptions(onConflict = OnConflict.UPDATE),
            ).close()
        }

        ex.message shouldBe "Target table 'writer_no_pk' has no primary key; onConflict=update requires a primary key"
    }

    test("onConflict skip reports inserted vs skipped") {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO writer_upsert_target (id, name) VALUES (1, 'existing')")
            }
        }

        writer.openTable(
            pool,
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
            writer.openTable(pool, "writer_child", ImportOptions()).use { session ->
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
            pool,
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

        writer.openTable(pool, "writer_parent", ImportOptions()).use { session ->
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
            pool,
            "writer_child",
            ImportOptions(disableFkChecks = true),
        ).use { session ->
            session.finishTable()
        }

        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA foreign_keys").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }

    test("onConflict update supports composite primary keys in key sequence order") {
        writer.openTable(
            pool,
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
            pool,
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

    test("primary key lookup handles mixed case table names consistently") {
        writer.openTable(
            pool,
            "mixedcasetarget",
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            session.write(
                chunk(
                    table = "mixedcasetarget",
                    columnNames = listOf("id", "name"),
                    rows = listOf(arrayOf<Any?>(1, "first")),
                )
            )
            session.commitChunk()
            session.finishTable()
        }

        writer.openTable(
            pool,
            "MixedCaseTarget",
            ImportOptions(onConflict = OnConflict.UPDATE),
        ).use { session ->
            val result = session.write(
                chunk(
                    table = "MixedCaseTarget",
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
