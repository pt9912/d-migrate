package dev.dmigrate.streaming

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.sqlite.SqliteDataWriter
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.DefaultDataChunkReaderFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class StreamingImporterSqliteTest : FunSpec({

    lateinit var dbFile: Path
    lateinit var pool: ConnectionPool

    beforeEach {
        dbFile = Files.createTempFile("d-migrate-sqlite-streaming-", ".db")
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
                    "CREATE TABLE test_users (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "score REAL)"
                )
            }
        }
    }

    afterEach {
        runCatching {
            pool.close()
        }
        runCatching {
            dbFile.deleteIfExists()
        }
    }

    fun buildImporter(): StreamingImporter {
        val writer = SqliteDataWriter()
        return StreamingImporter(
            readerFactory = DefaultDataChunkReaderFactory(),
            writerLookup = { writer },
        )
    }

    fun queryAllUsers(pool: ConnectionPool): List<Triple<Long, String, Double?>> {
        val rows = mutableListOf<Triple<Long, String, Double?>>()
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT id, name, score FROM test_users ORDER BY id").use { rs ->
                    while (rs.next()) {
                        rows += Triple(
                            rs.getLong("id"),
                            rs.getString("name"),
                            if (rs.getObject("score") != null) rs.getDouble("score") else null,
                        )
                    }
                }
            }
        }
        return rows
    }

    test("sqlite importer round-trips json into target table") {
        val jsonFile = Files.createTempFile("streaming-json-", ".json")
        try {
            jsonFile.writeText(
                """[{"id":10,"name":"alice","score":3.14},{"id":11,"name":"bob","score":2.71}]"""
            )

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", jsonFile),
                format = DataExportFormat.JSON,
            )

            result.success shouldBe true
            result.totalRowsInserted shouldBe 2
            val rows = queryAllUsers(pool)
            rows shouldHaveSize 2
            rows[0] shouldBe Triple(10L, "alice", 3.14)
            rows[1] shouldBe Triple(11L, "bob", 2.71)
        } finally {
            jsonFile.deleteIfExists()
        }
    }

    test("sqlite importer reorders header columns before write") {
        val jsonFile = Files.createTempFile("streaming-reorder-", ".json")
        try {
            jsonFile.writeText(
                """[{"score":9.99,"name":"charlie","id":20}]"""
            )

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", jsonFile),
                format = DataExportFormat.JSON,
            )

            result.success shouldBe true
            result.totalRowsInserted shouldBe 1
            val rows = queryAllUsers(pool)
            rows shouldHaveSize 1
            rows[0] shouldBe Triple(20L, "charlie", 9.99)
        } finally {
            jsonFile.deleteIfExists()
        }
    }

    test("sqlite importer reports skipped rows for onConflict skip") {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO test_users (id, name, score) VALUES (1, 'existing', 1.0)")
            }
        }

        val jsonFile = Files.createTempFile("streaming-skip-", ".json")
        try {
            jsonFile.writeText(
                """[{"id":1,"name":"ignored","score":0.0},{"id":2,"name":"inserted","score":5.0}]"""
            )

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", jsonFile),
                format = DataExportFormat.JSON,
                options = ImportOptions(onConflict = OnConflict.SKIP),
            )

            val summary = result.tables.single()
            result.success shouldBe true
            summary.error.shouldBeNull()
            summary.rowsSkipped shouldBe 1
            summary.rowsInserted shouldBe 1

            val rows = queryAllUsers(pool)
            rows shouldHaveSize 2
            rows[0] shouldBe Triple(1L, "existing", 1.0)
            rows[1] shouldBe Triple(2L, "inserted", 5.0)
        } finally {
            jsonFile.deleteIfExists()
        }
    }

    test("sqlite importer reports updated rows for onConflict update") {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("INSERT INTO test_users (id, name, score) VALUES (1, 'old', 1.0)")
            }
        }

        val jsonFile = Files.createTempFile("streaming-update-", ".json")
        try {
            jsonFile.writeText(
                """[{"id":1,"name":"updated","score":9.0},{"id":3,"name":"new","score":7.0}]"""
            )

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", jsonFile),
                format = DataExportFormat.JSON,
                options = ImportOptions(onConflict = OnConflict.UPDATE),
            )

            val summary = result.tables.single()
            result.success shouldBe true
            summary.error.shouldBeNull()
            summary.rowsUpdated shouldBe 1
            summary.rowsInserted shouldBe 1

            val rows = queryAllUsers(pool)
            rows shouldHaveSize 2
            rows[0] shouldBe Triple(1L, "updated", 9.0)
            rows[1] shouldBe Triple(3L, "new", 7.0)
        } finally {
            jsonFile.deleteIfExists()
        }
    }

    test("sqlite importer logs chunk failures for onError log") {
        val jsonFile = Files.createTempFile("streaming-error-", ".json")
        try {
            jsonFile.writeText(
                """[{"id":100,"name":null,"score":1.0},{"id":101,"name":"ok","score":2.0}]"""
            )

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", jsonFile),
                format = DataExportFormat.JSON,
                options = ImportOptions(onError = OnError.LOG),
                config = PipelineConfig(chunkSize = 1),
            )

            val summary = result.tables.single()
            summary.chunkFailures shouldHaveSize 1
            summary.chunkFailures.single().chunkIndex shouldBe 0L
            summary.rowsFailed shouldBe 1
            summary.error.shouldNotBeNull()
        } finally {
            jsonFile.deleteIfExists()
        }
    }

    test("sqlite importer reseeds sqlite_sequence after import") {
        val jsonFile = Files.createTempFile("streaming-reseed-", ".json")
        try {
            jsonFile.writeText(
                """[{"id":50,"name":"high-id","score":0.0}]"""
            )

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", jsonFile),
                format = DataExportFormat.JSON,
            )

            val summary = result.tables.single()
            result.success shouldBe true
            summary.error.shouldBeNull()
            summary.sequenceAdjustments shouldHaveSize 1
            summary.sequenceAdjustments.single().column shouldBe "id"
            summary.sequenceAdjustments.single().newValue shouldBe 51L

            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("INSERT INTO test_users (name, score) VALUES ('auto', 0.0)")
                }
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT id FROM test_users WHERE name = 'auto'").use { rs ->
                        rs.next() shouldBe true
                        rs.getLong("id") shouldBe 51L
                    }
                }
            }
        } finally {
            jsonFile.deleteIfExists()
        }
    }

    test("sqlite importer round-trips csv into target table") {
        val csvFile = Files.createTempFile("streaming-csv-", ".csv")
        try {
            csvFile.writeText("id,name,score\n30,eve,4.5\n31,frank,6.7\n")

            val result = buildImporter().import(
                pool = pool,
                input = ImportInput.SingleFile("test_users", csvFile),
                format = DataExportFormat.CSV,
            )

            result.success shouldBe true
            result.totalRowsInserted shouldBe 2
            val rows = queryAllUsers(pool)
            rows shouldHaveSize 2
            rows[0] shouldBe Triple(30L, "eve", 4.5)
            rows[1] shouldBe Triple(31L, "frank", 6.7)
        } finally {
            csvFile.deleteIfExists()
        }
    }
})
