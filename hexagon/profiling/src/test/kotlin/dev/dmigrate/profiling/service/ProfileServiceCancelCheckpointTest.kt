package dev.dmigrate.profiling.service

import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.cancel.TestCancellationTokenSource
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027 checkpoint guard for the profiling pipeline:
 * - cancel before `listTables` halts before the first introspection call
 * - cancel between table iterations starts no further table profiling
 * - cancel between column iterations starts no further column profiling
 * - cancel between `data.*` queries starts no further query
 *
 * Cancel surfaces as [OperationCancelledException], never as
 * `ProfilingQueryError` or `SchemaIntrospectionError` (LF-012 / LN-011 / LN-017 / LN-027).
 */
class ProfileServiceCancelCheckpointTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    class CountingPorts {
        val listTablesCalls = AtomicInteger(0)
        val listColumnsCalls = AtomicInteger(0)
        val rowCountCalls = AtomicInteger(0)
        val columnMetricsCalls = AtomicInteger(0)
        val topValuesCalls = AtomicInteger(0)
        val numericStatsCalls = AtomicInteger(0)
        val temporalStatsCalls = AtomicInteger(0)
        val targetCompatCalls = AtomicInteger(0)
    }

    fun adaptersFor(
        counts: CountingPorts,
        tables: List<String> = listOf("t1", "t2"),
        columns: List<ColumnSchema> = listOf(
            ColumnSchema("id", "INTEGER", false, isPrimaryKey = true),
            ColumnSchema("name", "TEXT", true),
        ),
    ): ProfilingAdapterSet {
        val intro = object : SchemaIntrospectionPort {
            override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> {
                counts.listTablesCalls.incrementAndGet()
                return tables.map { TableSchema(it) }
            }
            override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> {
                counts.listColumnsCalls.incrementAndGet()
                return columns
            }
        }
        val data = object : ProfilingDataPort {
            override fun rowCount(pool: ConnectionPool, table: String, schema: String?): Long {
                counts.rowCountCalls.incrementAndGet()
                return 0L
            }
            override fun columnMetrics(
                pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?,
            ): ColumnMetrics {
                counts.columnMetricsCalls.incrementAndGet()
                return ColumnMetrics(0, 0, 0, 0)
            }
            override fun topValues(
                pool: ConnectionPool, table: String, column: String, limit: Int, schema: String?,
            ): List<ValueFrequency> {
                counts.topValuesCalls.incrementAndGet()
                return emptyList()
            }
            override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?) =
                null.also { counts.numericStatsCalls.incrementAndGet() }
            override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?) =
                null.also { counts.temporalStatsCalls.incrementAndGet() }
            override fun targetTypeCompatibility(
                pool: ConnectionPool, table: String, column: String,
                targetTypes: List<TargetLogicalType>, schema: String?,
            ): List<TargetTypeCompatibility> {
                counts.targetCompatCalls.incrementAndGet()
                return emptyList()
            }
        }
        val resolver = object : LogicalTypeResolverPort {
            override fun resolve(dbType: String): LogicalType =
                if (dbType == "INTEGER") LogicalType.INTEGER else LogicalType.STRING
        }
        return ProfilingAdapterSet(intro, data, resolver)
    }

    // ── ProfileDatabaseService ──────────────────────────────────

    test("cancel before listTables halts before any introspection call") {
        val counts = CountingPorts()
        val service = ProfileDatabaseService(adaptersFor(counts))
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0) }

        shouldThrow<OperationCancelledException> {
            service.profile(pool = pool, databaseProduct = "test", cancellationToken = source.token)
        }
        counts.listTablesCalls.get() shouldBe 0
    }

    test("cancel between table iterations starts no further table profiling") {
        val counts = CountingPorts()
        val source = TestCancellationTokenSource()
        val tablesProfiled = mutableListOf<String>()
        val service = ProfileDatabaseService(
            adaptersFor(counts, tables = listOf("t1", "t2")),
            tableService = object : ProfileTableService(adaptersFor(counts)) {
                override fun profile(
                    pool: ConnectionPool,
                    tableName: String,
                    schema: String?,
                    cancellationToken: dev.dmigrate.core.cancel.CancellationToken,
                ): dev.dmigrate.profiling.model.TableProfile {
                    tablesProfiled += tableName
                    if (tableName == "t1") source.cancel("after-t1")
                    return dev.dmigrate.profiling.model.TableProfile(
                        name = tableName, rowCount = 0L, columns = emptyList(),
                    )
                }
            },
        )

        shouldThrow<OperationCancelledException> {
            service.profile(pool = pool, databaseProduct = "test", cancellationToken = source.token)
        }
        tablesProfiled shouldBe listOf("t1")
    }

    // ── ProfileTableService ─────────────────────────────────────

    test("cancel before listColumns halts before introspection") {
        val counts = CountingPorts()
        val service = ProfileTableService(adaptersFor(counts))
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0) }

        shouldThrow<OperationCancelledException> {
            service.profile(pool, "t1", null, source.token)
        }
        counts.listColumnsCalls.get() shouldBe 0
        counts.rowCountCalls.get() shouldBe 0
    }

    test("cancel between listColumns and rowCount halts before rowCount") {
        val counts = CountingPorts()
        val service = ProfileTableService(adaptersFor(counts))
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(1) }

        shouldThrow<OperationCancelledException> {
            service.profile(pool, "t1", null, source.token)
        }
        counts.listColumnsCalls.get() shouldBe 1
        counts.rowCountCalls.get() shouldBe 0
    }

    test("cancel between column iterations starts no further column profiling") {
        val counts = CountingPorts()
        val service = ProfileTableService(adaptersFor(counts))
        // Default columns: id INTEGER (triggers numericStats path) + name TEXT.
        // Checkpoint sequence inside ProfileTableService.profile(t1) for that
        // column shape:
        //   #1 entry (before listColumns)
        //   #2 before rowCount
        //   #3 column-1 iteration (id)
        //   #4 profileColumn(id) before columnMetrics
        //   #5 before topValues
        //   #6 before numericStats (INTEGER → active)
        //   #7 before targetTypeCompatibility
        //   #8 column-2 iteration (name) — should throw before profileColumn
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(7) }

        shouldThrow<OperationCancelledException> {
            service.profile(pool, "t1", null, source.token)
        }
        // Exactly column 1 was fully profiled before cancel halted column 2.
        counts.columnMetricsCalls.get() shouldBe 1
        counts.topValuesCalls.get() shouldBe 1
        counts.numericStatsCalls.get() shouldBe 1
        counts.targetCompatCalls.get() shouldBe 1
    }

    test("cancel between columnMetrics and topValues starts no topValues query") {
        val counts = CountingPorts()
        val service = ProfileTableService(adaptersFor(counts, columns = listOf(
            ColumnSchema("id", "TEXT", true), // string-typed → no numericStats path
        )))
        // Order:
        //   #1 entry
        //   #2 before rowCount
        //   #3 before column-1 profileColumn
        //   #4 before columnMetrics (inside profileColumn)
        //   #5 before topValues
        // Cancel after 4 checkpoints fires before topValues.
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(4) }

        shouldThrow<OperationCancelledException> {
            service.profile(pool, "t1", null, source.token)
        }
        counts.columnMetricsCalls.get() shouldBe 1
        counts.topValuesCalls.get() shouldBe 0
    }
})
