package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.PoolSettings
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.SchemaCodec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Types

/**
 * `DataSeedWiring` gegen eine Fake-Factory (Review-Fixes: `database.pool:`
 * war zuvor still ignoriert, `writeRows` schrieb immer einen unbatchten
 * Chunk). Deckt genau die Verdrahtungspfade ab, die
 * [DefaultDataSeedWiringFactory] real konstruiert.
 */
class DataSeedWiringTest : FunSpec({

    val schema = SchemaDefinition(
        name = "t", version = "1.0",
        tables = mapOf(
            "widgets" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), required = true, unique = true),
                ),
            ),
        ),
    )

    fun fakeSchemaCodec() = object : SchemaCodec {
        override fun read(input: InputStream) = error("unused")
        override fun read(path: Path) = schema
        override fun write(output: OutputStream, schema: SchemaDefinition) = error("unused")
    }

    fun fakeSession() = object : TableImportSession {
        override val targetColumns = listOf(TargetColumn("id", false, Types.INTEGER))
        val writtenChunks = mutableListOf<DataChunk>()
        override fun write(chunk: DataChunk): WriteResult {
            writtenChunks += chunk
            return WriteResult(chunk.rows.size.toLong(), 0, 0)
        }
        override fun commitChunk() {}
        override fun rollbackChunk() {}
        override fun markTruncatePerformed() {}
        override fun finishTable() = FinishTableResult.Success(emptyList())
        override fun close() {}
    }

    fun options(
        pool: PoolSettings = PoolSettings(),
        chunkSize: Int = DataSeedRequest.DEFAULT_CHUNK_SIZE,
        count: Int = 5,
        rulesFile: Path? = null,
    ) = DataSeedOptions(
        schema = Path.of("unused.yaml"),
        target = "sqlite:///tmp/x.db",
        count = count,
        seed = 1L,
        locale = "en",
        configPath = null,
        pool = pool,
        chunkSize = chunkSize,
        rulesFile = rulesFile,
    )

    class RecordingFactory(private val session: TableImportSession) : DataSeedWiringFactory {
        val poolConfigs = mutableListOf<ConnectionConfig>()
        override fun build() = DataSeedWiringBundle(
            schemaCodec = fakeSchemaCodec(),
            targetResolver = { target, _ -> target ?: "sqlite:///tmp/x.db" },
            urlParser = { ConnectionConfig(DatabaseDialect.SQLITE, "h", null, "d", null, null) },
            poolFactory = { config ->
                poolConfigs += config
                object : ConnectionPool {
                    override val dialect = DatabaseDialect.SQLITE
                    override fun borrow(): DatabaseConnection = error("unused")
                    override fun activeConnections() = 0
                    override fun close() {}
                }
            },
            writerLookup = {
                object : DataWriter {
                    override val dialect = DatabaseDialect.SQLITE
                    override fun schemaSync(): SchemaSync = error("unused")
                    override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) = session
                }
            },
        )
    }

    test("pool:-wiring injects the resolved PoolSettings into the ConnectionConfig") {
        val configured = PoolSettings(maximumPoolSize = 5, minimumIdle = 1, connectionTimeoutMs = 15_000)
        val factory = RecordingFactory(fakeSession())

        val exit = DataSeedWiring.execute(options(pool = configured), factory)

        exit shouldBe 0
        factory.poolConfigs.single().pool shouldBe configured
    }

    test("chunk_size-wiring batches writes into multiple DataChunks") {
        val session = fakeSession()
        val factory = RecordingFactory(session)

        val exit = DataSeedWiring.execute(options(count = 5, chunkSize = 2), factory)

        exit shouldBe 0
        session.writtenChunks.map { it.rows.size } shouldBe listOf(2, 2, 1)
    }

    test("default factory reuses the shared target resolver and writer lookup") {
        val bundle = DefaultDataSeedWiringFactory.build()
        bundle.targetResolver("sqlite:///tmp/x.db", null) shouldBe "sqlite:///tmp/x.db"
    }

    test("an invalid --rules file maps to exit 7 without ever calling the factory (AP4)") {
        val rulesFile = Files.createTempFile("data-seed-wiring-test-", ".yaml")
        Files.writeString(rulesFile, "notRules: []\n")
        val factory = RecordingFactory(fakeSession())

        val exit = DataSeedWiring.execute(options(rulesFile = rulesFile), factory)

        exit shouldBe 7
        factory.poolConfigs shouldBe emptyList()
    }

    test("a valid --rules file is loaded and applied by the runner (AP4)") {
        val rulesFile = Files.createTempFile("data-seed-wiring-test-", ".yaml")
        Files.writeString(rulesFile, "rules:\n  - column: id\n    range:\n      min: 1\n      max: 1\n")
        val session = fakeSession()
        val factory = RecordingFactory(session)

        val exit = DataSeedWiring.execute(options(rulesFile = rulesFile, count = 1), factory)

        exit shouldBe 0
        val written = session.writtenChunks.single().rows.single().single()
        written shouldBe 1L
    }
})
