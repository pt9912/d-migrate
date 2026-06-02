package dev.dmigrate.profiling.service

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection

/**
 * LF-012 / LN-011 / LN-017 / LN-027 propagation guard: a token passed to [ProfileDatabaseService.profile]
 * must reach [ProfileTableService.profile] for every iterated table without being
 * swallowed at the outer service.
 */
class ProfileDatabaseServiceCancelPropagationTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    val introspection = object : SchemaIntrospectionPort {
        override fun listTables(pool: ConnectionPool, schema: String?) = listOf(
            TableSchema("t1"), TableSchema("t2"),
        )
        override fun listColumns(pool: ConnectionPool, table: String, schema: String?) =
            emptyList<ColumnSchema>()
    }
    val data = object : ProfilingDataPort {
        override fun rowCount(pool: ConnectionPool, table: String, schema: String?) = 0L
        override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?) =
            throw UnsupportedOperationException("not used")
        override fun topValues(pool: ConnectionPool, table: String, column: String, limit: Int, schema: String?) =
            emptyList<dev.dmigrate.profiling.model.ValueFrequency>()
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
    val resolver = object : LogicalTypeResolverPort {
        override fun resolve(dbType: String) = LogicalType.STRING
    }
    val adapters = ProfilingAdapterSet(introspection, data, resolver)

    test("cancellationToken is forwarded to ProfileTableService.profile for every iterated table") {
        val capturedTokens = mutableListOf<CancellationToken>()
        val capturingTableService = object : ProfileTableService(adapters) {
            override fun profile(
                pool: ConnectionPool,
                tableName: String,
                schema: String?,
                cancellationToken: CancellationToken,
            ): TableProfile {
                capturedTokens += cancellationToken
                return TableProfile(name = tableName, rowCount = 0L, columns = emptyList())
            }
        }
        val service = ProfileDatabaseService(adapters, tableService = capturingTableService)

        val token = CancellationTokenSource.create().token
        service.profile(
            pool = pool,
            databaseProduct = "test",
            cancellationToken = token,
        )

        capturedTokens.size shouldBe 2
        capturedTokens.forEach { (it === token) shouldBe true }
    }

    test("default cancellationToken is CancellationToken.none() when not provided") {
        val capturedTokens = mutableListOf<CancellationToken>()
        val capturingTableService = object : ProfileTableService(adapters) {
            override fun profile(
                pool: ConnectionPool,
                tableName: String,
                schema: String?,
                cancellationToken: CancellationToken,
            ): TableProfile {
                capturedTokens += cancellationToken
                return TableProfile(name = tableName, rowCount = 0L, columns = emptyList())
            }
        }
        val service = ProfileDatabaseService(adapters, tableService = capturingTableService)

        service.profile(pool = pool, databaseProduct = "test")

        capturedTokens.first().isCancellationRequested shouldBe false
    }
})
