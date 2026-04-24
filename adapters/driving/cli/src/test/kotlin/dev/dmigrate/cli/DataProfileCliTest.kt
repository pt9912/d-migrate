package dev.dmigrate.cli

import dev.dmigrate.cli.commands.DataProfileCommand
import dev.dmigrate.cli.commands.DataProfileRequest
import dev.dmigrate.cli.commands.DataProfileRunner
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.model.DatabaseProfile
import dev.dmigrate.profiling.model.ColumnProfile
import dev.dmigrate.profiling.model.TableProfile
import dev.dmigrate.profiling.model.ValueFrequency
import dev.dmigrate.profiling.port.ColumnMetrics
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.LogicalTypeResolverPort
import dev.dmigrate.profiling.port.ProfilingDataPort
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import dev.dmigrate.profiling.types.LogicalType
import dev.dmigrate.format.report.ProfileReportWriter
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.sql.Connection

class DataProfileCliTest : FunSpec({

    val fakePool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    val fakeIntrospection = object : SchemaIntrospectionPort {
        override fun listTables(pool: ConnectionPool, schema: String?) = listOf(TableSchema("t"))
        override fun listColumns(pool: ConnectionPool, table: String, schema: String?) = listOf(
            ColumnSchema("id", "INTEGER", false, isPrimaryKey = true),
        )
    }

    val fakeData = object : ProfilingDataPort {
        override fun rowCount(pool: ConnectionPool, table: String, schema: String?) = 5L
        override fun columnMetrics(pool: ConnectionPool, table: String, column: String, dbType: String, schema: String?) =
            ColumnMetrics(5, 0, 5, 0)
        override fun topValues(
            pool: ConnectionPool,
            table: String,
            column: String,
            limit: Int,
            schema: String?,
        ) = emptyList<ValueFrequency>()
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
        override fun resolve(dbType: String) = LogicalType.INTEGER
    }

    val fakeAdapters = ProfilingAdapterSet(fakeIntrospection, fakeData, fakeResolver)

    fun buildRunner(
        stdout: StringBuilder,
        fileOutput: MutableMap<String, String> = mutableMapOf(),
    ): DataProfileRunner {
        val writer = ProfileReportWriter()
        return DataProfileRunner(
            connectionResolver = { it },
            dialectResolver = { DatabaseDialect.SQLITE },
            poolFactory = { _, _ -> fakePool },
            adapterLookup = { fakeAdapters },
            databaseProduct = { "SQLite" },
            databaseVersion = { null },
            reportWriter = { profile, format, output ->
                val content = if (format == "yaml") writer.renderYaml(profile) else writer.renderJson(profile)
                if (output != null) {
                    fileOutput[output.toString()] = content
                } else {
                    stdout.append(content)
                }
            },
        )
    }

    // ── JSON on stdout ───────────────────────────────────

    test("JSON round-trip on stdout") {
        val stdout = StringBuilder()
        val runner = buildRunner(stdout)
        val exit = runner.execute(DataProfileRequest(source = "sqlite::memory:"))
        exit shouldBe 0
        stdout.toString() shouldContain "\"databaseProduct\": \"SQLite\""
        stdout.toString() shouldContain "\"name\": \"t\""
        stdout.toString() shouldContain "\"logicalType\": \"INTEGER\""
    }

    // ── YAML to file ─────────────────────────────────────

    test("YAML round-trip to file") {
        val stdout = StringBuilder()
        val files = mutableMapOf<String, String>()
        val runner = buildRunner(stdout, files)
        val tempFile = Files.createTempFile("profile", ".yaml")
        val exit = runner.execute(DataProfileRequest(
            source = "sqlite::memory:",
            format = "yaml",
            output = tempFile,
        ))
        exit shouldBe 0
        val yaml = files[tempFile.toString()]!!
        yaml shouldContain "databaseProduct: SQLite"
        yaml shouldContain "name: t"
        yaml shouldContain "logicalType: INTEGER"
        tempFile.toFile().delete()
    }

    // ── JSON and YAML carry same fields ──────────────────

    test("JSON and YAML carry same core fields") {
        val jsonOut = StringBuilder()
        val yamlOut = StringBuilder()
        buildRunner(jsonOut).execute(DataProfileRequest(source = "sqlite::memory:", format = "json"))
        buildRunner(yamlOut).execute(DataProfileRequest(source = "sqlite::memory:", format = "yaml"))

        val json = jsonOut.toString()
        val yaml = yamlOut.toString()

        // Both must contain these core fields
        for (field in listOf("databaseProduct", "name", "rowCount", "nonNullCount", "logicalType")) {
            json shouldContain field
            yaml shouldContain field
        }
    }

    // ── Table filter ─────────────────────────────────────

    test("table filter passes through") {
        val stdout = StringBuilder()
        val runner = buildRunner(stdout)
        val exit = runner.execute(DataProfileRequest(
            source = "sqlite::memory:",
            tables = listOf("t"),
        ))
        exit shouldBe 0
        stdout.toString() shouldContain "\"name\": \"t\""
    }
})
