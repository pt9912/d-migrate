package dev.dmigrate.driver.mssql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

// Slice 3 (docs/planning/in-progress/mssql-dialect-scoping.md): Datenpfad gegen
// echtes SQL Server 2022 — IDENTITY_INSERT, MERGE-Konfliktmodi, DBCC-Reseed,
// gefilterter Index (SET-Optionen) und Geometrie-WKB-Round-Trip.
class MssqlDataPathIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var pool: ConnectionPool

    fun column(name: String) = ColumnDescriptor(name = name, nullable = true)

    fun chunk(table: String, columns: List<String>, vararg rows: Array<Any?>) = DataChunk(
        table = table,
        columns = columns.map(::column),
        rows = rows.toList(),
        chunkIndex = 0,
    )

    fun queryRows(sql: String): List<List<Any?>> =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                        }
                    }
                }
            }
        }

    fun importChunk(
        table: String,
        columns: List<String>,
        rows: List<Array<Any?>>,
        options: ImportOptions = ImportOptions(),
    ) = MssqlDataWriter().openTable(pool, table, options).use { session ->
        val result = session.write(DataChunk(table, columns.map(::column), rows, 0))
        session.commitChunk()
        val finish = session.finishTable()
        result to finish
    }

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MssqlDriver())
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE dmigrate_data") }
        }
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MSSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = "dmigrate_data",
                user = container.username,
                password = container.password,
                ssl = SslSettings(SslMode.DISABLE),
            )
        )
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE customers (
                        id INT IDENTITY(1,1) NOT NULL,
                        name NVARCHAR(100) NOT NULL,
                        state NVARCHAR(20) NULL,
                        CONSTRAINT pk_customers PRIMARY KEY (id)
                    )
                    """.trimIndent(),
                )
                // Gefilterter Index: DML darauf verlangt die SET-Optionen der Session.
                stmt.execute("CREATE INDEX ix_customers_open ON customers(state) WHERE state = N'open'")
                stmt.execute(
                    """
                    CREATE TABLE places (
                        id INT NOT NULL PRIMARY KEY,
                        planar geometry NULL,
                        globe geography NULL
                    )
                    """.trimIndent(),
                )
                stmt.execute("CREATE TABLE plain (id INT NOT NULL PRIMARY KEY, label NVARCHAR(50) NULL)")
                stmt.execute(
                    """
                    CREATE TABLE child (
                        id INT NOT NULL PRIMARY KEY,
                        parent_id INT NOT NULL,
                        CONSTRAINT fk_child_plain FOREIGN KEY (parent_id) REFERENCES plain(id)
                    )
                    """.trimIndent(),
                )
            }
        }
    }

    afterSpec {
        pool.close()
        container.stop()
    }

    test("insert with explicit identity values needs IDENTITY_INSERT and reseeds afterwards") {
        val (result, finish) = importChunk(
            table = "customers",
            columns = listOf("id", "name", "state"),
            rows = listOf(arrayOf<Any?>(5, "alice", "open"), arrayOf<Any?>(9, "bob", null)),
        )
        result.rowsInserted shouldBe 2
        finish.shouldBeSuccessWithReseed(expectedNext = 10L)

        queryRows("SELECT id, name, state FROM customers ORDER BY id") shouldContainExactly listOf(
            listOf(5, "alice", "open"),
            listOf(9, "bob", null),
        )

        // Der Reseed muss greifen: der naechste server-vergebene Wert folgt auf 9.
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO customers (name) VALUES (N'carol')") }
        }
        queryRows("SELECT id FROM customers WHERE name = N'carol'") shouldContainExactly listOf(listOf(10))
    }

    test("skip and update run through MERGE with exact row accounting") {
        importChunk("plain", listOf("id", "label"), listOf(arrayOf<Any?>(1, "one")))

        val (skip, _) = importChunk(
            "plain", listOf("id", "label"),
            listOf(arrayOf<Any?>(1, "ignored"), arrayOf<Any?>(2, "two")),
            ImportOptions(onConflict = OnConflict.SKIP),
        )
        skip.rowsInserted shouldBe 1
        skip.rowsSkipped shouldBe 1
        queryRows("SELECT label FROM plain WHERE id = 1") shouldContainExactly listOf(listOf("one"))

        val (update, _) = importChunk(
            "plain", listOf("id", "label"),
            listOf(arrayOf<Any?>(1, "updated"), arrayOf<Any?>(3, "three")),
            ImportOptions(onConflict = OnConflict.UPDATE),
        )
        update.rowsUpdated shouldBe 1
        update.rowsInserted shouldBe 1
        queryRows("SELECT id, label FROM plain ORDER BY id") shouldContainExactly listOf(
            listOf(1, "updated"), listOf(2, "two"), listOf(3, "three"),
        )
    }

    test("geometry and geography round-trip as WKB through reader and writer") {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO places (id, planar, globe) VALUES (" +
                        "1, geometry::STGeomFromText('POINT(10 20)', 0), " +
                        "geography::STGeomFromText('POINT(11.58 48.14)', 4326))",
                )
            }
        }

        val chunks = MssqlDataReader().streamTable(pool, "places").use { it.toList() }
        val row = chunks.flatMap { it.rows }.single()
        val planarWkb = row[1] as ByteArray
        val globeWkb = row[2] as ByteArray
        planarWkb.size shouldBe 21
        globeWkb.size shouldBe 21

        // Zurueckschreiben: der Writer konstruiert aus dem WKB wieder geometry/geography.
        importChunk("places", listOf("id", "planar", "globe"), listOf(arrayOf<Any?>(2, planarWkb, globeWkb)))
        queryRows(
            "SELECT planar.STAsText(), globe.STAsText(), globe.STSrid FROM places WHERE id = 2",
        ) shouldContainExactly listOf(listOf("POINT (10 20)", "POINT (11.58 48.14)", 4326))
    }

    test("truncateTables clears FK-referenced tables and the writer restores the constraints") {
        importChunk("child", listOf("id", "parent_id"), listOf(arrayOf<Any?>(1, 1)))
        queryRows("SELECT COUNT(*) FROM child") shouldContainExactly listOf(listOf(1))

        MssqlDataWriter().truncateTables(pool, listOf("child", "plain"))
        queryRows("SELECT COUNT(*) FROM child") shouldContainExactly listOf(listOf(0))
        queryRows("SELECT COUNT(*) FROM plain") shouldContainExactly listOf(listOf(0))

        // FK ist wieder scharf: eine verwaiste Zeile muss abgelehnt werden.
        val orphanRejected = runCatching {
            importChunk("child", listOf("id", "parent_id"), listOf(arrayOf<Any?>(2, 999)))
        }.isFailure
        orphanRejected shouldBe true
    }

    test("streaming an empty table still yields the column header") {
        MssqlDataWriter().truncateTables(pool, listOf("customers"))
        val chunks = MssqlDataReader().streamTable(pool, "customers").use { it.toList() }
        chunks shouldHaveSize 1
        chunks.single().columns.map { it.name } shouldContainExactly listOf("id", "name", "state")
        chunks.single().rows.shouldBeEmpty()
    }
})

private fun FinishTableResult.shouldBeSuccessWithReseed(expectedNext: Long) {
    val success = this as FinishTableResult.Success
    success.adjustments.single().newValue shouldBe expectedNext
}
