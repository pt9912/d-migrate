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
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.math.BigDecimal
import java.sql.DriverManager

/**
 * Der BulkCopy-Weg des SQL-Server-Imports gegen ein echtes SQL Server 2022.
 *
 * BulkCopy prueft per Voreinstellung **keine** Constraints und feuert **keine**
 * Trigger — beides hier gemessen, bevor der Pfad gebaut wurde. Der Import darf
 * durch den schnelleren Weg aber nichts anderes tun als vorher, und genau das
 * belegen die Faelle unten: sie schlagen fehl, sobald eine der beiden
 * erzwungenen Optionen wegfaellt. Damit belegen sie zugleich, dass der Weg
 * ueberhaupt genommen wird — auf dem INSERT-Weg haette das Umlegen einer
 * BulkCopy-Option keine Wirkung.
 */
class MssqlBulkImportIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MssqlDriver())
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE dmigrate_bulk") }
        }
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MSSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = "dmigrate_bulk",
                user = container.username,
                password = container.password,
                ssl = SslSettings(SslMode.DISABLE),
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(vararg sqls: String) = pool.borrow().asJdbc().use { c ->
        c.createStatement().use { s -> sqls.forEach { s.execute(it) } }
    }

    fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(read(rs)) } }
            }
        }

    fun import(
        table: String,
        columns: List<String>,
        rows: List<Array<Any?>>,
        options: ImportOptions = ImportOptions(),
    ) = MssqlDataWriter().openTable(pool, table, options).use { session ->
        val result = session.write(DataChunk(table, columns.map { ColumnDescriptor(it, nullable = true) }, rows, 0))
        session.commitChunk()
        session.finishTable()
        result
    }

    test("a scalar chunk lands with its values, keys and neighbours intact") {
        exec(
            """IF OBJECT_ID('dbo.bulk_plain') IS NOT NULL DROP TABLE dbo.bulk_plain""",
            """CREATE TABLE dbo.bulk_plain (
                 [id] INT NOT NULL PRIMARY KEY,
                 [name] NVARCHAR(100) NOT NULL,
                 [amount] DECIMAL(18,2) NOT NULL,
                 [note] NVARCHAR(400) NULL)""",
        )

        val rows = (1..5_000).map { i ->
            arrayOf<Any?>(i, "name-$i", BigDecimal("$i.25"), if (i % 2 == 0) null else "note-$i")
        }
        val result = import("bulk_plain", listOf("id", "name", "amount", "note"), rows)

        result.rowsInserted shouldBe 5_000L
        query("SELECT COUNT_BIG(*) FROM dbo.bulk_plain") { it.getLong(1) }.single() shouldBe 5_000L
        // Die Randwerte, damit ein stiller Versatz auffiele.
        query("""SELECT [name], [amount], [note] FROM dbo.bulk_plain WHERE [id] = 4999""") {
            listOf(it.getString(1), it.getBigDecimal(2), it.getString(3))
        }.single() shouldContainExactly listOf("name-4999", BigDecimal("4999.25"), "note-4999")
        query("""SELECT [note] FROM dbo.bulk_plain WHERE [id] = 5000""") { it.getString(1) }
            .single() shouldBe null
    }

    test("a CHECK constraint still rejects — the bulk default would have let it through") {
        exec(
            """IF OBJECT_ID('dbo.bulk_guard') IS NOT NULL DROP TABLE dbo.bulk_guard""",
            """CREATE TABLE dbo.bulk_guard (
                 [id] INT NOT NULL PRIMARY KEY,
                 [amount] INT NOT NULL CONSTRAINT ck_bulk_guard CHECK ([amount] > 0))""",
        )

        val failed = runCatching {
            import("bulk_guard", listOf("id", "amount"), listOf(arrayOf<Any?>(1, -5)))
        }
        withClue("erwartet: Ablehnung, bekommen: ${failed.getOrNull()}") { failed.isFailure shouldBe true }
        query("SELECT COUNT_BIG(*) FROM dbo.bulk_guard") { it.getLong(1) }.single() shouldBe 0L
    }

    test("an AFTER INSERT trigger still fires — the bulk default would have kept it silent") {
        exec(
            """IF OBJECT_ID('dbo.bulk_audit') IS NOT NULL DROP TABLE dbo.bulk_audit""",
            """IF OBJECT_ID('dbo.bulk_source') IS NOT NULL DROP TABLE dbo.bulk_source""",
            """CREATE TABLE dbo.bulk_audit ([n] INT NOT NULL)""",
            """CREATE TABLE dbo.bulk_source ([id] INT NOT NULL PRIMARY KEY, [name] NVARCHAR(50) NOT NULL)""",
        )
        exec(
            """CREATE TRIGGER trg_bulk_source ON dbo.bulk_source AFTER INSERT AS
               INSERT INTO dbo.bulk_audit ([n]) SELECT COUNT(*) FROM inserted""",
        )

        import("bulk_source", listOf("id", "name"), (1..10).map { arrayOf<Any?>(it, "n$it") })

        query("SELECT SUM([n]) FROM dbo.bulk_audit") { it.getInt(1) }.single() shouldBe 10
    }

    test("explicit values for an IDENTITY column survive") {
        exec(
            """IF OBJECT_ID('dbo.bulk_identity') IS NOT NULL DROP TABLE dbo.bulk_identity""",
            """CREATE TABLE dbo.bulk_identity (
                 [id] INT IDENTITY(1,1) NOT NULL PRIMARY KEY,
                 [name] NVARCHAR(50) NOT NULL)""",
        )

        import("bulk_identity", listOf("id", "name"), listOf(arrayOf<Any?>(7, "seven"), arrayOf<Any?>(42, "answer")))

        query("SELECT [id] FROM dbo.bulk_identity ORDER BY [id]") { it.getInt(1) } shouldContainExactly listOf(7, 42)
    }

    test("the conflict modes keep their MERGE semantics — BulkCopy has no place there") {
        exec(
            """IF OBJECT_ID('dbo.bulk_conflict') IS NOT NULL DROP TABLE dbo.bulk_conflict""",
            """CREATE TABLE dbo.bulk_conflict ([id] INT NOT NULL PRIMARY KEY, [name] NVARCHAR(50) NOT NULL)""",
            """INSERT INTO dbo.bulk_conflict ([id], [name]) VALUES (1, N'old')""",
        )

        val skipped = import(
            "bulk_conflict", listOf("id", "name"),
            listOf(arrayOf<Any?>(1, "new"), arrayOf<Any?>(2, "fresh")),
            ImportOptions(onConflict = OnConflict.SKIP),
        )

        skipped.rowsInserted shouldBe 1L
        skipped.rowsSkipped shouldBe 1L
        query("SELECT [name] FROM dbo.bulk_conflict WHERE [id] = 1") { it.getString(1) }.single() shouldBe "old"
    }
})
