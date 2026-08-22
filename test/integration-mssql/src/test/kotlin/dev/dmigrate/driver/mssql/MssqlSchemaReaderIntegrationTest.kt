package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as strShouldContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

// Slice 1 (docs/planning/in-progress/mssql-dialect-scoping.md): Reverse-Read
// gegen echtes SQL Server 2022 — Identity/Defaults/gefilterte Indizes kommen
// aus sys.*-Sichten, nicht aus INFORMATION_SCHEMA (Plan-Risiko).
class MssqlSchemaReaderIntegrationTest : FunSpec({

    val container = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
        .acceptLicense()
        .withUrlParam("encrypt", "false")

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MssqlDriver())
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE DATABASE dmigrate_test")
            }
        }
        config = ConnectionConfig(
            dialect = DatabaseDialect.MSSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_test",
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
                            id INT IDENTITY(1,1) PRIMARY KEY,
                            name NVARCHAR(100) NOT NULL,
                            email NVARCHAR(254) NULL,
                            score DECIMAL(5,2) NOT NULL DEFAULT ((0)),
                            active BIT NOT NULL DEFAULT ((1)),
                            created_at DATETIMEOFFSET NOT NULL DEFAULT (sysdatetimeoffset()),
                            CONSTRAINT uq_customers_email UNIQUE (email),
                            CONSTRAINT ck_customers_score CHECK (score >= 0)
                        )
                        """.trimIndent(),
                    )
                    stmt.execute(
                        """
                        CREATE TABLE orders (
                            id BIGINT IDENTITY(1,1) PRIMARY KEY,
                            customer_id INT NOT NULL,
                            state NVARCHAR(20) NOT NULL,
                            CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id)
                                REFERENCES customers(id) ON DELETE CASCADE
                        )
                        """.trimIndent(),
                    )
                    stmt.execute("CREATE INDEX ix_orders_state_open ON orders(state) WHERE state = N'open'")
                    stmt.execute("CREATE SEQUENCE order_seq AS BIGINT START WITH 100 INCREMENT BY 5")
                    // CREATE VIEW/PROCEDURE muessen jeweils allein im Batch stehen.
                    stmt.execute("CREATE VIEW v_active AS SELECT id, name FROM customers WHERE active = 1")
                    stmt.execute("CREATE PROCEDURE usp_noop AS BEGIN SELECT 1 END")
                }
            }
        }
    }

    test("schema reverse reads tables, sequences, views and flags unread routines") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val result = MssqlSchemaReader().read(pool)

            result.schema.name shouldBe "__dmigrate_reverse__:mssql:database=dmigrate_test;schema=dbo"

            val customers = result.schema.tables.getValue("customers")
            customers.primaryKey shouldBe listOf("id")
            customers.columns.getValue("id").type shouldBe NeutralType.Identifier(autoIncrement = true)
            customers.columns.getValue("name").let {
                it.type shouldBe NeutralType.Text(100)
                it.required shouldBe true
            }
            customers.columns.getValue("email").unique shouldBe true
            customers.columns.getValue("score").default shouldBe DefaultValue.NumberLiteral(0L)
            customers.columns.getValue("active").let {
                it.type shouldBe NeutralType.BooleanType
                it.default shouldBe DefaultValue.BooleanLiteral(true)
            }
            customers.columns.getValue("created_at").let {
                it.type shouldBe NeutralType.DateTime(timezone = true)
                it.default shouldBe DefaultValue.FunctionCall("current_timestamp")
            }
            customers.constraints.first { it.name == "ck_customers_score" }
                .expression.shouldNotBeNull() strShouldContain "score"

            val orders = result.schema.tables.getValue("orders")
            orders.columns.getValue("id").let {
                it.type shouldBe NeutralType.BigInteger
                it.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
            }
            val fk = orders.constraints.first { it.name == "fk_orders_customer" }
            fk.references!!.table shouldBe "customers"
            fk.references!!.columns shouldBe listOf("id")
            val filtered = orders.indices.first { it.name == "ix_orders_state_open" }
            filtered.where.shouldNotBeNull() strShouldContain "open"

            val seq = result.schema.sequences.getValue("order_seq")
            seq.start shouldBe 100L
            seq.increment shouldBe 5L
            seq.minValue.shouldBeNull()
            seq.maxValue.shouldBeNull()

            result.schema.views.getValue("v_active").query.shouldNotBeNull() strShouldContain "SELECT"

            result.skippedObjects.map { it.name } shouldContain "usp_noop"
            result.notes.first { it.code == "R342" }.message strShouldContain "usp_noop"
        }
    }

    test("table lister returns the dbo tables") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            MssqlTableLister().listTables(pool) shouldBe listOf("customers", "orders")
        }
    }
})
