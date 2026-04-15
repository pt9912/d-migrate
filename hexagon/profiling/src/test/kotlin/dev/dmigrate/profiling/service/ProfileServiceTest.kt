package dev.dmigrate.profiling.service

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.SchemaIntrospectionError
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.types.LogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.sql.Connection

class ProfileServiceTest : FunSpec({

    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    val columns = listOf(
        ColumnSchema("id", "INTEGER", false, isPrimaryKey = true),
        ColumnSchema("name", "TEXT", true),
    )

    val introspection = object : SchemaIntrospectionPort {
        override fun listTables(pool: ConnectionPool, schema: String?) = listOf(
            TableSchema("users"), TableSchema("orders"),
        )
        override fun listColumns(pool: ConnectionPool, table: String, schema: String?) = columns
    }

    val data = object : ProfilingDataPort {
        override fun rowCount(pool: ConnectionPool, table: String) = 5L
        override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String) =
            ColumnMetrics(5, 0, 5, 0)
        override fun topValues(pool: ConnectionPool, table: String, column: String, limit: Int) =
            listOf(ValueFrequency("a", 3, 0.6), ValueFrequency("b", 2, 0.4))
        override fun numericStats(pool: ConnectionPool, table: String, column: String) = null
        override fun temporalStats(pool: ConnectionPool, table: String, column: String) = null
        override fun targetTypeCompatibility(pool: ConnectionPool, table: String, column: String, targetTypes: List<dev.dmigrate.profiling.types.TargetLogicalType>) = emptyList<dev.dmigrate.profiling.model.TargetTypeCompatibility>()
    }

    val resolver = object : LogicalTypeResolverPort {
        override fun resolve(dbType: String) =
            if (dbType == "INTEGER") LogicalType.INTEGER else LogicalType.STRING
    }

    val adapters = ProfilingAdapterSet(introspection, data, resolver)

    // ── ProfileTableService ──────────────────────────────

    test("profiles a table with columns, metrics, and warnings") {
        val service = ProfileTableService(adapters)
        val profile = service.profile(fakePool, "users")
        profile.name shouldBe "users"
        profile.rowCount shouldBe 5
        profile.columns shouldHaveSize 2
        profile.columns[0].logicalType shouldBe LogicalType.INTEGER
        profile.columns[1].logicalType shouldBe LogicalType.STRING
    }

    test("column topValues are populated") {
        val service = ProfileTableService(adapters)
        val profile = service.profile(fakePool, "users")
        profile.columns[1].topValues shouldHaveSize 2
    }

    test("introspection error throws SchemaIntrospectionError") {
        val badIntrospection = object : SchemaIntrospectionPort {
            override fun listTables(pool: ConnectionPool, schema: String?) = emptyList<TableSchema>()
            override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> =
                throw RuntimeException("metadata error")
        }
        val service = ProfileTableService(ProfilingAdapterSet(badIntrospection, data, resolver))
        shouldThrow<SchemaIntrospectionError> {
            service.profile(fakePool, "users")
        }
    }

    test("type resolver error throws TypeResolutionError") {
        val badResolver = object : LogicalTypeResolverPort {
            override fun resolve(dbType: String): LogicalType =
                throw RuntimeException("unknown type: $dbType")
        }
        val service = ProfileTableService(ProfilingAdapterSet(introspection, data, badResolver))
        shouldThrow<dev.dmigrate.profiling.TypeResolutionError> {
            service.profile(fakePool, "users")
        }
    }

    // ── ProfileDatabaseService ───────────────────────────

    test("profiles all tables in alphabetical order") {
        val service = ProfileDatabaseService(adapters)
        val db = service.profile(fakePool, "PostgreSQL", "16.1")
        db.tables shouldHaveSize 2
        db.tables[0].name shouldBe "orders"
        db.tables[1].name shouldBe "users"
        db.databaseProduct shouldBe "PostgreSQL"
    }

    test("filters to specific tables in caller order") {
        val service = ProfileDatabaseService(adapters)
        val db = service.profile(fakePool, "PostgreSQL", tables = listOf("users"))
        db.tables shouldHaveSize 1
        db.tables[0].name shouldBe "users"
    }

    test("filter with unknown table is silently skipped") {
        val service = ProfileDatabaseService(adapters)
        val db = service.profile(fakePool, "PostgreSQL", tables = listOf("nonexistent", "users"))
        db.tables shouldHaveSize 1
        db.tables[0].name shouldBe "users"
    }

    test("deterministic table order without filter") {
        val service = ProfileDatabaseService(adapters)
        val a = service.profile(fakePool, "PostgreSQL")
        val b = service.profile(fakePool, "PostgreSQL")
        a.tables.map { it.name } shouldBe b.tables.map { it.name }
    }
})
