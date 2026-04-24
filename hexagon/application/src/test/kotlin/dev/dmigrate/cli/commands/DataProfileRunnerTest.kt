package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.sql.Connection

class DataProfileRunnerTest : FunSpec({

    val stderrLines = mutableListOf<String>()
    var lastProfile: DatabaseProfile? = null

    val fakeIntrospection = object : SchemaIntrospectionPort {
        override fun listTables(pool: ConnectionPool, schema: String?) = listOf(TableSchema("users"))
        override fun listColumns(pool: ConnectionPool, table: String, schema: String?) = listOf(
            ColumnSchema("id", "INTEGER", false, isPrimaryKey = true),
            ColumnSchema("name", "TEXT", true),
        )
    }

    val fakeData = object : ProfilingDataPort {
        override fun rowCount(pool: ConnectionPool, table: String, schema: String?) = 10L
        override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?) =
            ColumnMetrics(nonNullCount = 10, nullCount = 0, distinctCount = 10, duplicateValueCount = 0)
        override fun topValues(
            pool: ConnectionPool,
            table: String,
            column: String,
            limit: Int,
            schema: String?,
        ) = emptyList<dev.dmigrate.profiling.model.ValueFrequency>()
        override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?) = null
        override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?) = null
        override fun targetTypeCompatibility(
            pool: ConnectionPool,
            table: String,
            column: String,
            targetTypes: List<dev.dmigrate.profiling.types.TargetLogicalType>,
            schema: String?,
        ) = emptyList<dev.dmigrate.profiling.model.TargetTypeCompatibility>()
    }

    val fakeResolver = object : LogicalTypeResolverPort {
        override fun resolve(dbType: String) = LogicalType.STRING
    }

    val fakeAdapters = ProfilingAdapterSet(fakeIntrospection, fakeData, fakeResolver)

    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    fun runner(
        connectionResolver: (String) -> String = { "postgresql://localhost/test" },
        dialectResolver: (String) -> DatabaseDialect = { DatabaseDialect.POSTGRESQL },
        poolFactory: (String, DatabaseDialect) -> AutoCloseable = { _, _ -> fakePool },
        adapterLookup: (DatabaseDialect) -> ProfilingAdapterSet = { fakeAdapters },
    ) = DataProfileRunner(
        connectionResolver = connectionResolver,
        dialectResolver = dialectResolver,
        poolFactory = poolFactory,
        adapterLookup = adapterLookup,
        databaseProduct = { "PostgreSQL" },
        databaseVersion = { "16.1" },
        reportWriter = { profile, _, _ -> lastProfile = profile },
        stderr = { stderrLines += it },
    )

    fun request(
        source: String = "mydb",
        tables: List<String>? = null,
        schema: String? = null,
        topN: Int = 10,
    ) = DataProfileRequest(source, tables, schema, topN)

    beforeEach {
        stderrLines.clear()
        lastProfile = null
    }

    // ── Exit 0: success ──────────────────────────────────

    test("successful profiling returns 0") {
        runner().execute(request()) shouldBe 0
        lastProfile?.tables?.size shouldBe 1
        lastProfile?.databaseProduct shouldBe "PostgreSQL"
    }

    // ── Exit 2: request validation ───────────────────────

    test("topN < 1 returns exit 2") {
        runner().execute(request(topN = 0)) shouldBe 2
        stderrLines.any { it.contains("topN") } shouldBe true
    }

    test("topN > 1000 returns exit 2") {
        runner().execute(request(topN = 1001)) shouldBe 2
    }

    test("schema on MySQL is allowed") {
        runner(
            dialectResolver = { DatabaseDialect.MYSQL }
        ).execute(request(schema = "myschema")) shouldBe 0
    }

    test("schema on SQLite returns exit 2") {
        runner(
            dialectResolver = { DatabaseDialect.SQLITE }
        ).execute(request(schema = "main")) shouldBe 2
    }

    test("schema on PostgreSQL is allowed") {
        runner().execute(request(schema = "public")) shouldBe 0
    }

    // ── Exit 4: connection error ─────────────────────────

    test("connection failure returns exit 4") {
        runner(
            poolFactory = { _, _ -> throw RuntimeException("connection refused") }
        ).execute(request()) shouldBe 4
        stderrLines.any { it.contains("Connection failed") } shouldBe true
    }

    // ── Exit 5: profiling error ──────────────────────────

    test("profiling exception returns exit 5") {
        val badIntrospection = object : SchemaIntrospectionPort {
            override fun listTables(pool: ConnectionPool, schema: String?) =
                throw dev.dmigrate.profiling.SchemaIntrospectionError("metadata unavailable")
            override fun listColumns(pool: ConnectionPool, table: String, schema: String?) = emptyList<ColumnSchema>()
        }
        runner(
            adapterLookup = { ProfilingAdapterSet(badIntrospection, fakeData, fakeResolver) }
        ).execute(request()) shouldBe 5
        stderrLines.any { it.contains("Profiling failed") } shouldBe true
    }

    // ── Exit 7: config/URL/registry error ────────────────

    test("connection resolver failure returns exit 7") {
        runner(
            connectionResolver = { throw RuntimeException("unknown alias") }
        ).execute(request()) shouldBe 7
    }

    test("dialect resolver failure returns exit 7") {
        runner(
            dialectResolver = { throw RuntimeException("bad URL") }
        ).execute(request()) shouldBe 7
    }

    test("missing adapter returns exit 7") {
        runner(
            adapterLookup = { throw RuntimeException("no adapter") }
        ).execute(request()) shouldBe 7
        stderrLines.any { it.contains("No profiling adapter") } shouldBe true
    }
})
