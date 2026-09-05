package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.core.data.ColumnDescriptor
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
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.streaming.CheckpointConfig
import dev.dmigrate.streaming.ImportInput
import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.ProgressEvent
import dev.dmigrate.streaming.ProgressOperation
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.TableImportSummary
import dev.dmigrate.streaming.checkpoint.CheckpointManifest
import dev.dmigrate.streaming.checkpoint.CheckpointReference
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.Types
import java.util.Comparator

class DataImportWiringTest : FunSpec({

    fun options(
        target: String? = null,
        source: String,
        format: String? = null,
        schema: Path? = null,
        table: String? = "items",
        truncate: Boolean = false,
        csvNoHeader: Boolean = false,
        chunkSize: Int = 64,
        checkpointDir: Path? = null,
        cliContext: CliContext = CliContext(quiet = true),
        configPath: Path? = Path.of(".d-migrate-test.yaml"),
        pool: PoolSettings = PoolSettings(),
    ) = DataImportOptions(
        target = target,
        source = source,
        format = format,
        schema = schema,
        table = table,
        tables = null,
        onError = "abort",
        onConflict = null,
        triggerMode = "fire",
        truncate = truncate,
        atomic = false,
        disableFkChecks = false,
        reseedSequences = true,
        encoding = null,
        csvNoHeader = csvNoHeader,
        csvNullString = "",
        chunkSize = chunkSize,
        parallel = 1,
        resume = null,
        checkpointDir = checkpointDir,
        noCheckpoint = false,
        cliContext = cliContext,
        configPath = configPath,
        pool = pool,
    )

    test("wires all fake bundle collaborators through a file import") {
        val dir = Files.createTempDirectory("dmigrate-import-wiring-")
        val source = dir.resolve("items.json")
        val schema = dir.resolve("schema.yaml")
        val checkpointDir = dir.resolve("checkpoints")
        val cliContext = CliContext(quiet = false)
        val factory = RecordingDataImportFactory()
        try {
            Files.writeString(source, "[]")
            Files.writeString(schema, "schema: fake")

            val exit = DataImportWiring.execute(
                options(
                    source = source.toString(),
                    schema = schema,
                    truncate = true,
                    csvNoHeader = true,
                    checkpointDir = checkpointDir,
                    cliContext = cliContext,
                ),
                factory,
            )

            exit shouldBe 0
            factory.buildContexts shouldBe listOf(cliContext)
            factory.targetRequests shouldBe listOf(null to Path.of(".d-migrate-test.yaml"))
            factory.parsedUrls shouldBe listOf("sqlite://import.db")
            factory.poolConfigs shouldBe listOf(factory.connectionConfig)
            factory.writerLookups shouldBe listOf(DatabaseDialect.SQLITE)
            factory.preflightCodecs shouldBe listOf(factory.schemaCodec)
            factory.schemaCodec.reads shouldBe 1
            factory.checkpointConfigRequests shouldBe listOf(Path.of(".d-migrate-test.yaml"))
            factory.checkpointStoreDirs shouldBe listOf(checkpointDir)
            factory.createdPools.single().closed shouldBe true

            val call = factory.executorCalls.single()
            call.pool shouldBe factory.createdPools.single()
            call.input shouldBe ImportInput.SingleFile("items", source)
            call.options.format shouldBe DataExportFormat.JSON
            call.options.options.truncate shouldBe true
            call.options.readOptions.csvNoHeader shouldBe true
            call.options.config.chunkSize shouldBe 64
            call.resume.resuming shouldBe false

            factory.progressEvents.single() shouldBe
                ProgressEvent.RunStarted(ProgressOperation.IMPORT, totalTables = 1)
            factory.checkpointStores.single().saved.isNotEmpty().shouldBeTrue()
            factory.checkpointStores.single().completed shouldBe listOf(call.resume.operationId)
        } finally {
            deleteRecursively(dir)
        }
    }

    test("pool:-wiring injects the resolved PoolSettings into the ConnectionConfig") {
        val dir = Files.createTempDirectory("dmigrate-import-pool-")
        val source = dir.resolve("items.json")
        val schema = dir.resolve("schema.yaml")
        val factory = RecordingDataImportFactory()
        val configured = PoolSettings(maximumPoolSize = 9, minimumIdle = 4, connectionTimeoutMs = 25_000)
        try {
            Files.writeString(source, "[]")
            Files.writeString(schema, "schema: fake")

            val exit = DataImportWiring.execute(
                options(source = source.toString(), schema = schema, pool = configured),
                factory,
            )

            exit shouldBe 0
            factory.poolConfigs.single().pool shouldBe configured
        } finally {
            deleteRecursively(dir)
        }
    }

    test("target usage failure returns exit 2 before url parsing or pool creation") {
        val dir = Files.createTempDirectory("dmigrate-import-target-")
        val source = dir.resolve("items.json")
        val factory = RecordingDataImportFactory(
            targetFailure = CliUsageException("missing target"),
        )
        try {
            Files.writeString(source, "[]")

            val exit = DataImportWiring.execute(
                options(source = source.toString(), format = "json"),
                factory,
            )

            exit shouldBe 2
            factory.targetRequests shouldBe listOf(null to Path.of(".d-migrate-test.yaml"))
            factory.parsedUrls shouldBe emptyList()
            factory.poolConfigs shouldBe emptyList()
            factory.executorCalls shouldBe emptyList()
        } finally {
            deleteRecursively(dir)
        }
    }

    test("default factory exposes collaborators without opening a pool") {
        val bundle = DefaultDataImportWiringFactory.build(CliContext(quiet = true))
        val dir = Files.createTempDirectory("dmigrate-import-default-")
        val schemaPath = dir.resolve("schema.yaml")
        val sourcePath = dir.resolve("items.json")
        val fakeCodec = RecordingSchemaCodec()
        try {
            Files.writeString(schemaPath, "schema: fake")
            Files.writeString(sourcePath, "[]")

            bundle.targetResolver("sqlite://import.db", null) shouldBe "sqlite://import.db"
            bundle.urlParser("sqlite://import.db").dialect shouldBe DatabaseDialect.SQLITE
            // Oracle: DialectCommandGate weist data import an der Kommando-
            // Grenze ab (ADR 0052, Slice 3 offen) -- OracleDriver.dataWriter()
            // ist bewusst ein "unreachable"-Stub, kein echter Writer.
            (DatabaseDialect.entries - DatabaseDialect.ORACLE).forEach { dialect ->
                bundle.writerLookup(dialect).dialect shouldBe dialect
            }
            bundle.schemaCodec::class.simpleName shouldBe "YamlSchemaCodec"

            val preflight = bundle.preflightFactory(fakeCodec)
            val prepared = preflight.prepare(
                schemaPath,
                ImportInput.SingleFile("items", sourcePath),
                DataExportFormat.JSON,
            )
            prepared.schema?.name shouldBe "Import"
            fakeCodec.reads shouldBe 1
            bundle.checkpointConfigResolver(null) shouldBe null
        } finally {
            deleteRecursively(dir)
        }
    }

    test("default execute path is callable without an explicit factory before target resolution") {
        val exit = DataImportWiring.execute(
            options(
                target = "sqlite://import.db",
                source = "-",
                format = "json",
                table = null,
                configPath = null,
            )
        )

        exit shouldBe 2
    }
})

private data class ImportExecutorCall(
    val pool: ConnectionPool,
    val input: ImportInput,
    val options: ImportExecutionOptions,
    val resume: ImportResumeState,
)

private class RecordingDataImportFactory(
    private val targetFailure: RuntimeException? = null,
) : DataImportWiringFactory {

    val connectionConfig = ConnectionConfig(DatabaseDialect.SQLITE, "localhost", null, "import", null, null)
    val schemaCodec = RecordingSchemaCodec()
    val buildContexts = mutableListOf<CliContext>()
    val targetRequests = mutableListOf<Pair<String?, Path?>>()
    val parsedUrls = mutableListOf<String>()
    val poolConfigs = mutableListOf<ConnectionConfig>()
    val writerLookups = mutableListOf<DatabaseDialect>()
    val preflightCodecs = mutableListOf<SchemaCodec>()
    val checkpointConfigRequests = mutableListOf<Path?>()
    val checkpointStoreDirs = mutableListOf<Path>()
    val checkpointStores = mutableListOf<RecordingCheckpointStore>()
    val createdPools = mutableListOf<FakeImportPool>()
    val executorCalls = mutableListOf<ImportExecutorCall>()
    val progressEvents = mutableListOf<ProgressEvent>()

    override fun build(cliContext: CliContext): DataImportWiringBundle {
        buildContexts.add(cliContext)
        return DataImportWiringBundle(
            targetResolver = { target, configPath ->
                targetRequests.add(target to configPath)
                targetFailure?.let { throw it }
                "sqlite://import.db"
            },
            urlParser = { url ->
                parsedUrls.add(url)
                connectionConfig
            },
            poolFactory = { config ->
                poolConfigs.add(config)
                FakeImportPool(config.dialect).also { createdPools.add(it) }
            },
            writerLookup = { dialect ->
                writerLookups.add(dialect)
                FakeImportWriter(dialect)
            },
            schemaCodec = schemaCodec,
            preflightFactory = { codec ->
                preflightCodecs.add(codec)
                DataImportSchemaPreflight(codec)
            },
            importExecutor = ImportExecutor { context, options, resume, callbacks ->
                executorCalls.add(ImportExecutorCall(context.pool, context.input, options, resume))
                callbacks.progressReporter.report(
                    ProgressEvent.RunStarted(ProgressOperation.IMPORT, totalTables = 1)
                )
                callbacks.onTableOpened(
                    "items",
                    listOf(TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER")),
                )
                val summary = TableImportSummary(
                    table = "items",
                    rowsInserted = 1,
                    rowsUpdated = 0,
                    rowsSkipped = 0,
                    rowsUnknown = 0,
                    rowsFailed = 0,
                    chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(),
                    targetColumns = listOf(ColumnDescriptor("id", nullable = false)),
                    triggerMode = options.options.triggerMode,
                    durationMs = 1,
                )
                callbacks.onTableCompleted(summary)
                ImportResult(
                    tables = listOf(summary),
                    totalRowsInserted = 1,
                    totalRowsUpdated = 0,
                    totalRowsSkipped = 0,
                    totalRowsUnknown = 0,
                    totalRowsFailed = 0,
                    durationMs = 1,
                    operationId = resume.operationId,
                )
            },
            progressReporter = ProgressReporter { event -> progressEvents.add(event) },
            checkpointStoreFactory = { dir ->
                checkpointStoreDirs.add(dir)
                RecordingCheckpointStore().also { checkpointStores.add(it) }
            },
            checkpointConfigResolver = { configPath ->
                checkpointConfigRequests.add(configPath)
                CheckpointConfig()
            },
        )
    }
}

private class RecordingSchemaCodec(
    private val schema: SchemaDefinition = validImportSchema(),
) : SchemaCodec {
    var reads = 0

    override fun read(input: InputStream): SchemaDefinition {
        reads++
        return schema
    }

    override fun write(output: OutputStream, schema: SchemaDefinition) = Unit
}

private class RecordingCheckpointStore : CheckpointStore {
    val saved = mutableListOf<CheckpointManifest>()
    val completed = mutableListOf<String>()

    override fun load(operationId: String): CheckpointManifest? = saved.lastOrNull { it.operationId == operationId }

    override fun save(manifest: CheckpointManifest) {
        saved.add(manifest)
    }

    override fun list(): List<CheckpointReference> = emptyList()

    override fun complete(operationId: String) {
        completed.add(operationId)
    }
}

private class FakeImportWriter(
    override val dialect: DatabaseDialect,
) : DataWriter {
    override fun schemaSync(): SchemaSync = object : SchemaSync {
        override fun reseedGenerators(
            conn: DatabaseConnection,
            table: String,
            importedColumns: List<ColumnDescriptor>,
        ): List<SequenceAdjustment> = emptyList()
    }

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession = throw UnsupportedOperationException("not used by wiring test")
}

private class FakeImportPool(
    override val dialect: DatabaseDialect,
) : ConnectionPool {
    var closed = false

    override fun borrow(): DatabaseConnection = throw UnsupportedOperationException("not used by wiring test")

    override fun activeConnections() = 0

    override fun close() {
        closed = true
    }
}

private fun validImportSchema() = SchemaDefinition(
    name = "Import",
    version = "1.0.0",
    tables = mapOf(
        "items" to TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, required = true),
            ),
            primaryKey = listOf("id"),
        ),
    ),
)

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }
}
