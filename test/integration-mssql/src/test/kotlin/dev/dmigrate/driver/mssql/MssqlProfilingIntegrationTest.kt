package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.mssql.profiling.MssqlLogicalTypeResolver
import dev.dmigrate.driver.mssql.profiling.MssqlProfilingDataAdapter
import dev.dmigrate.driver.mssql.profiling.MssqlSchemaIntrospectionAdapter
import dev.dmigrate.profiling.ProfilingAdapterSet
import dev.dmigrate.profiling.service.ProfileDatabaseService
import dev.dmigrate.profiling.service.ProfileTableService
import dev.dmigrate.profiling.types.LogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

/**
 * `data profile` gegen echtes SQL Server 2022.
 *
 * Der Schwerpunkt liegt auf den Typen, die T-SQL nicht vergleichen kann:
 * `geometry`, `xml` und die LOB-Alttypen weisen `COUNT(DISTINCT)` und
 * `GROUP BY` ab, `image` laesst sich zusaetzlich nicht nach `nvarchar` wandeln.
 * Ohne die Projektion scheitert das Profil an ihnen, statt sie zu beschreiben.
 */
class MssqlProfilingIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MssqlDriver())
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("CREATE DATABASE dmigrate_profile") }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_profile",
            user = container.username,
            password = container.password,
            ssl = SslSettings(SslMode.DISABLE),
        )
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(
                        """
                        CREATE TABLE customers (
                            id INT IDENTITY(1,1) CONSTRAINT pk_customers PRIMARY KEY,
                            email NVARCHAR(200) CONSTRAINT uq_customers_email UNIQUE,
                            name NVARCHAR(100) NULL,
                            score DECIMAL(10,2) NULL,
                            joined DATETIME2 NULL,
                            shape GEOMETRY NULL,
                            doc XML NULL,
                            legacy TEXT NULL
                        )
                        """.trimIndent(),
                    )
                    stmt.execute(
                        "INSERT INTO customers (email, name, score, joined, shape, doc, legacy) VALUES " +
                            "(N'a@x.de', N'Ann', 10.5, '2024-01-01', geometry::Point(1,2,0), '<a/>', 'one'), " +
                            "(N'b@x.de', N'Bob', -3.0, '2024-06-01', geometry::Point(3,4,0), '<b/>', 'two'), " +
                            "(N'c@x.de', N'   ', 0.0, NULL, NULL, NULL, NULL)",
                    )
                }
            }
        }
    }

    afterSpec { container.stop() }

    fun adapters() = ProfilingAdapterSet(
        MssqlSchemaIntrospectionAdapter(),
        MssqlProfilingDataAdapter(),
        MssqlLogicalTypeResolver(),
    )

    test("a table with spatial, xml and legacy LOB columns profiles without failing") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val set = adapters()
            val profile = ProfileDatabaseService(set, ProfileTableService(set))
                .profile(pool = pool, databaseProduct = "mssql")

            val table = profile.tables.single { it.name == "customers" }
            table.rowCount shouldBe 3
            val columns = table.columns.associateBy { it.name }

            columns.getValue("name").nullCount shouldBe 0
            // Drei Namen, davon einer nur Leerraum.
            columns.getValue("name").blankStringCount shouldBe 1

            // Die Spalten, an denen ein naiver COUNT(DISTINCT) scheitert.
            columns.getValue("shape").logicalType shouldBe LogicalType.GEOMETRY
            columns.getValue("shape").distinctCount shouldBe 2
            columns.getValue("shape").nullCount shouldBe 1
            columns.getValue("doc").distinctCount shouldBe 2
            columns.getValue("legacy").distinctCount shouldBe 2

            // Und die Textform, die dabei herauskommt.
            columns.getValue("shape").topValues.map { it.value } shouldContain "POINT (1 2)"

            columns.getValue("score").numericStats.shouldNotBeNull().negativeCount shouldBe 1
            columns.getValue("joined").temporalStats.shouldNotBeNull()
                .minTimestamp shouldBe "2024-01-01T00:00:00"
        }
    }

    // Schluessel- und Unique-Eigenschaften stehen im Introspektions-Port, nicht
    // im Profil.
    test("keys and unique columns come from the sys catalog") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val schema = MssqlSchemaIntrospectionAdapter().listColumns(pool, "customers").associateBy { it.name }
            schema.getValue("id").isPrimaryKey shouldBe true
            schema.getValue("email").isUnique shouldBe true
            schema.getValue("name").isUnique shouldBe false
            schema.getValue("shape").dbType shouldBe "geometry"
            MssqlSchemaIntrospectionAdapter().listTables(pool).map { it.name } shouldContain "customers"
        }
    }
})
