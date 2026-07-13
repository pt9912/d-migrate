package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.model.TargetTypeCompatibility
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

class DataProfileWiringTest : FunSpec({

    fun options(
        dialect: DatabaseDialect = DatabaseDialect.SQLITE,
        tables: List<String>? = null,
        topN: Int = 4,
        configPath: Path? = null,
    ) = DataProfileOptions(
        source = "named-${dialect.name.lowercase()}",
        tables = tables,
        schema = null,
        topN = topN,
        format = "json",
        output = null,
        readOnly = true,
        cliContext = CliContext(quiet = true),
        configPath = configPath,
    )

    context("happy path by dialect") {
        DatabaseDialect.values().forEach { dialect ->
            test("wires fake profiling adapters for ${dialect.name.lowercase()}") {
                val tableName = tableNameFor(dialect)
                val configPath = Path.of(".d-migrate-test.yaml")
                val factory = RecordingDataProfileFactory(dialect)

                val exit = DataProfileWiring.execute(
                    options(
                        dialect = dialect,
                        tables = listOf(tableName),
                        topN = 6,
                        configPath = configPath,
                    ),
                    factory,
                )

                exit shouldBe 0
                factory.configPaths shouldBe listOf(configPath)
                factory.readOnlyRequests shouldBe listOf(true) // profile opens the source read-only by default

                factory.sources shouldBe listOf("named-${dialect.name.lowercase()}")
                factory.dialectUrls shouldBe listOf("${dialect.name.lowercase()}://profile-db")
                factory.poolRequests shouldBe listOf(dialect)
                factory.adapterLookups shouldBe listOf(dialect)
                factory.topValueLimits shouldBe listOf(6)
                factory.createdPools.single().closed shouldBe true
                factory.reportFormats shouldBe listOf("json")
                factory.reports.single().databaseProduct shouldBe "Fake ${dialect.name}"
                factory.reports.single().databaseVersion shouldBe "test-version"
                factory.reports.single().tables.single().name shouldBe tableName
            }
        }
    }

    test("pool construction failure returns exit 4 without adapter lookup or report write") {
        val factory = RecordingDataProfileFactory(
            dialect = DatabaseDialect.POSTGRESQL,
            poolFailure = RuntimeException("pool refused"),
        )

        val exit = DataProfileWiring.execute(options(DatabaseDialect.POSTGRESQL), factory)

        exit shouldBe 4
        factory.poolRequests shouldBe listOf(DatabaseDialect.POSTGRESQL)
        factory.adapterLookups shouldBe emptyList()
        factory.reports shouldBe emptyList()
        factory.createdPools shouldBe emptyList()
    }

    test("empty table filter writes an empty profile without column or data calls") {
        val factory = RecordingDataProfileFactory(DatabaseDialect.SQLITE)

        val exit = DataProfileWiring.execute(
            options(
                dialect = DatabaseDialect.SQLITE,
                tables = emptyList(),
            ),
            factory,
        )

        exit shouldBe 0
        factory.listTablesCalls shouldBe 1
        factory.listColumnsCalls shouldBe 0
        factory.dataCalls shouldBe 0
        factory.reports.single().tables shouldBe emptyList()
        factory.createdPools.single().closed shouldBe true
    }

    test("default factory resolves direct URLs and selects all profiling adapter sets") {
        val bundle = DefaultDataProfileWiringFactory.build(configPath = null, readOnly = true)

        bundle.connectionResolver("sqlite://profile.db") shouldBe "sqlite://profile.db"
        bundle.dialectResolver("postgresql://localhost/profile") shouldBe DatabaseDialect.POSTGRESQL
        bundle.dialectResolver("mysql://localhost/profile") shouldBe DatabaseDialect.MYSQL
        bundle.dialectResolver("sqlite::memory:") shouldBe DatabaseDialect.SQLITE

        DatabaseDialect.values().forEach { dialect ->
            val adapters = bundle.adapterLookup(dialect)
            adapters.introspection::class.simpleName?.isNotBlank() shouldBe true
            adapters.data::class.simpleName?.isNotBlank() shouldBe true
            adapters.typeResolver::class.simpleName?.isNotBlank() shouldBe true
        }
    }

    test("default factory report writer writes deterministic file output") {
        val bundle = DefaultDataProfileWiringFactory.build(configPath = null, readOnly = true)
        val output = Files.createTempFile("dmigrate-profile-wiring-", ".json")
        try {
            bundle.reportWriter(
                DatabaseProfile(databaseProduct = "test-db", tables = emptyList()),
                "json",
                output,
            )

            Files.readString(output) shouldContain "\"databaseProduct\": \"test-db\""
            Files.readString(output) shouldContain "\"tables\": ["
        } finally {
            Files.deleteIfExists(output)
        }
    }
})

private fun tableNameFor(dialect: DatabaseDialect) = "profile_${dialect.name.lowercase()}"

private class RecordingDataProfileFactory(
    private val dialect: DatabaseDialect,
    private val poolFailure: RuntimeException? = null,
) : DataProfileWiringFactory {

    val configPaths = mutableListOf<Path?>()
    val sources = mutableListOf<String>()
    val dialectUrls = mutableListOf<String>()
    val poolRequests = mutableListOf<DatabaseDialect>()
    val adapterLookups = mutableListOf<DatabaseDialect>()
    val createdPools = mutableListOf<FakeProfilePool>()
    val reports = mutableListOf<DatabaseProfile>()
    val reportFormats = mutableListOf<String>()
    val topValueLimits = mutableListOf<Int>()
    var listTablesCalls = 0
    var listColumnsCalls = 0
    var dataCalls = 0
    val readOnlyRequests = mutableListOf<Boolean>()

    override fun build(configPath: Path?, readOnly: Boolean): DataProfileWiringBundle {
        configPaths += configPath
        readOnlyRequests += readOnly
        return DataProfileWiringBundle(
            connectionResolver = { source ->
                sources += source
                "${dialect.name.lowercase()}://profile-db"
            },
            dialectResolver = { url ->
                dialectUrls += url
                dialect
            },
            poolFactory = { _, requestedDialect ->
                poolRequests += requestedDialect
                poolFailure?.let { throw it }
                FakeProfilePool(requestedDialect).also { createdPools += it }
            },
            adapterLookup = { requestedDialect ->
                adapterLookups += requestedDialect
                adaptersFor(requestedDialect)
            },
            databaseProduct = { pool -> "Fake ${(pool as FakeProfilePool).dialect.name}" },
            databaseVersion = { "test-version" },
            reportWriter = { profile, format, _ ->
                reports += profile
                reportFormats += format
            },
        )
    }

    private fun adaptersFor(requestedDialect: DatabaseDialect): ProfilingAdapterSet {
        val tableName = tableNameFor(requestedDialect)
        val introspection = object : SchemaIntrospectionPort {
            override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> {
                listTablesCalls++
                return listOf(TableSchema(tableName))
            }

            override fun listColumns(
                pool: ConnectionPool,
                table: String,
                schema: String?,
            ): List<ColumnSchema> {
                listColumnsCalls++
                return listOf(ColumnSchema("id", "INTEGER", nullable = false, isPrimaryKey = true))
            }
        }
        val data = object : ProfilingDataPort {
            override fun rowCount(pool: ConnectionPool, table: String, schema: String?): Long {
                dataCalls++
                return 2L
            }

            override fun columnMetrics(
                pool: ConnectionPool,
                table: String,
                column: String,
                dbType: String,
                schema: String?,
            ): ColumnMetrics {
                dataCalls++
                return ColumnMetrics(
                    nonNullCount = 2,
                    nullCount = 0,
                    distinctCount = 2,
                    duplicateValueCount = 0,
                )
            }

            override fun topValues(
                pool: ConnectionPool,
                table: String,
                column: String,
                limit: Int,
                schema: String?,
            ): List<ValueFrequency> {
                dataCalls++
                topValueLimits += limit
                return emptyList()
            }

            override fun numericStats(
                pool: ConnectionPool,
                table: String,
                column: String,
                schema: String?,
            ) = null

            override fun temporalStats(
                pool: ConnectionPool,
                table: String,
                column: String,
                schema: String?,
            ) = null

            override fun targetTypeCompatibility(
                pool: ConnectionPool,
                table: String,
                column: String,
                targetTypes: List<TargetLogicalType>,
                schema: String?,
            ): List<TargetTypeCompatibility> {
                dataCalls++
                return emptyList()
            }
        }
        val typeResolver = object : LogicalTypeResolverPort {
            override fun resolve(dbType: String) = LogicalType.INTEGER
        }
        return ProfilingAdapterSet(introspection, data, typeResolver)
    }
}

private class FakeProfilePool(
    override val dialect: DatabaseDialect,
) : ConnectionPool {
    var closed = false

    override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()

    override fun activeConnections() = 0

    override fun close() {
        closed = true
    }
}
