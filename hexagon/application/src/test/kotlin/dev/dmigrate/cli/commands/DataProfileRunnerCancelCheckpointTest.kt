package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.TestCancellationTokenSource
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027: Cancel inside the profiling pipeline must surface as exit 130
 * — never as the generic `5` profiling-error path. LF-012 / LN-011 / LN-017 / LN-027 forbids the
 * `OperationCancelledException` from being mapped to a fachlicher error
 * even when it travels through a `catch (e: ProfilingException)` neighbor.
 */
class DataProfileRunnerCancelCheckpointTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    val intro = object : SchemaIntrospectionPort {
        override fun listTables(pool: ConnectionPool, schema: String?) = listOf(TableSchema("t1"))
        override fun listColumns(pool: ConnectionPool, table: String, schema: String?) = listOf(
            ColumnSchema("id", "TEXT", false),
        )
    }
    val data = object : ProfilingDataPort {
        override fun rowCount(pool: ConnectionPool, table: String, schema: String?) = 0L
        override fun columnMetrics(
            pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?,
        ) = ColumnMetrics(0, 0, 0, 0)
        override fun topValues(
            pool: ConnectionPool, table: String, column: String, limit: Int, schema: String?,
        ) = emptyList<dev.dmigrate.profiling.model.ValueFrequency>()
        override fun numericStats(pool: ConnectionPool, table: String, column: String, schema: String?) = null
        override fun temporalStats(pool: ConnectionPool, table: String, column: String, schema: String?) = null
        override fun targetTypeCompatibility(
            pool: ConnectionPool, table: String, column: String,
            targetTypes: List<dev.dmigrate.profiling.types.TargetLogicalType>, schema: String?,
        ) = emptyList<dev.dmigrate.profiling.model.TargetTypeCompatibility>()
    }
    val resolver = object : LogicalTypeResolverPort {
        override fun resolve(dbType: String) = LogicalType.STRING
    }
    val adapters = ProfilingAdapterSet(intro, data, resolver)

    fun buildRunner(reportWritten: AtomicInteger) = DataProfileRunner(
        connectionResolver = { it },
        dialectResolver = { DatabaseDialect.SQLITE },
        poolFactory = { _, _ -> pool },
        adapterLookup = { adapters },
        databaseProduct = { "SQLite" },
        databaseVersion = { "3.x" },
        reportWriter = { _, _, _: Path? -> reportWritten.incrementAndGet(); Unit },
        stderr = { },
    )

    val request = DataProfileRequest(source = "sqlite:///x.db", quiet = true)

    test("cancel before any introspection returns 130 and writes no report") {
        val reportWritten = AtomicInteger(0)
        val runner = buildRunner(reportWritten)
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0) }

        runner.execute(request, source.token) shouldBe 130
        reportWritten.get() shouldBe 0
    }

    test("cancel between profiling and report writer returns 130 and writes no report") {
        val reportWritten = AtomicInteger(0)
        val runner = buildRunner(reportWritten)
        // Checkpoint sequence inside the runner+services for one table with one
        // STRING column (no numeric/temporal stats path):
        //   #1 ProfileDatabaseService.profile entry
        //   #2 outer table-loop iteration (t1)
        //   #3 ProfileTableService.profile entry (before listColumns)
        //   #4 before rowCount
        //   #5 before column-1 profileColumn
        //   #6 before columnMetrics
        //   #7 before topValues
        //   #8 before targetTypeCompatibility
        //   #9 DataProfileRunner pre-reportWriter checkpoint
        // Cancel after 8 lets profiling complete and stops at the report
        // writer checkpoint.
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(8) }

        runner.execute(request, source.token) shouldBe 130
        reportWritten.get() shouldBe 0
    }

    test("default token completes profiling and writes report (exit 0)") {
        val reportWritten = AtomicInteger(0)
        val runner = buildRunner(reportWritten)

        runner.execute(request) shouldBe 0
        reportWritten.get() shouldBe 1
    }
})
