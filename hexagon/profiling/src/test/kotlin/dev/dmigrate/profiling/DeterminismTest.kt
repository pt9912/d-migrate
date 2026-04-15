package dev.dmigrate.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.service.ProfileDatabaseService
import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection

/**
 * Explicit determinism contract tests for the profiling pipeline.
 * Verifies that identical inputs produce identical outputs.
 */
class DeterminismTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    // Tables returned in non-alphabetical order to verify sorting
    val introspection = object : SchemaIntrospectionPort {
        override fun listTables(pool: ConnectionPool, schema: String?) = listOf(
            TableSchema("orders"), TableSchema("accounts"), TableSchema("users"),
        )
        override fun listColumns(pool: ConnectionPool, table: String, schema: String?) = listOf(
            ColumnSchema("id", "INTEGER", false, isPrimaryKey = true),
            ColumnSchema("name", "TEXT", true),
        )
    }

    val data = object : ProfilingDataPort {
        override fun rowCount(pool: ConnectionPool, table: String, schema: String?) = 10L
        override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?) =
            ColumnMetrics(10, 0, 8, 2)
        override fun topValues(pool: ConnectionPool, table: String, column: String, limit: Int, schema: String?) =
            listOf(ValueFrequency("b", 4, 0.4), ValueFrequency("a", 3, 0.3), ValueFrequency("c", 3, 0.3))
        override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?) = null
        override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?) = null
        override fun targetTypeCompatibility(pool: ConnectionPool, table: String, column: String, targetTypes: List<dev.dmigrate.profiling.types.TargetLogicalType>, schema: String?) = emptyList<dev.dmigrate.profiling.model.TargetTypeCompatibility>()
    }

    val resolver = object : LogicalTypeResolverPort {
        override fun resolve(dbType: String) = if (dbType == "INTEGER") LogicalType.INTEGER else LogicalType.STRING
    }

    val adapters = ProfilingAdapterSet(introspection, data, resolver)
    val service = ProfileDatabaseService(adapters)

    test("table order is alphabetical and stable") {
        val profile = service.profile(pool, "PostgreSQL")
        profile.tables.map { it.name } shouldBe listOf("accounts", "orders", "users")
    }

    test("column order matches introspection order") {
        val profile = service.profile(pool, "PostgreSQL")
        profile.tables[0].columns.map { it.name } shouldBe listOf("id", "name")
    }

    test("topValues order is preserved from adapter") {
        val profile = service.profile(pool, "PostgreSQL")
        val topValues = profile.tables[0].columns[1].topValues
        topValues.map { it.value } shouldBe listOf("b", "a", "c")
    }

    test("DatabaseProfile has no generatedAt field") {
        val profile = service.profile(pool, "PostgreSQL")
        // DatabaseProfile is a data class — if generatedAt existed as a property,
        // this would not compile. This test documents the contract explicitly.
        val fields = DatabaseProfile::class.java.declaredFields.map { it.name }
        fields.contains("generatedAt") shouldBe false
    }

    test("identical inputs produce identical profiles") {
        val a = service.profile(pool, "PostgreSQL", "16.1")
        val b = service.profile(pool, "PostgreSQL", "16.1")
        a shouldBe b
    }

    test("profile is stable across repeated calls") {
        val results = (1..5).map { service.profile(pool, "PostgreSQL") }
        results.distinct().size shouldBe 1
    }
})
