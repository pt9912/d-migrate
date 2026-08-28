package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as strShouldContain
import org.testcontainers.mssqlserver.MSSQLServerContainer
import java.sql.DriverManager

// Reverse-Read gegen echtes SQL Server 2022. Identity, Defaults, gefilterte und
// abdeckende Indizes kommen aus den sys.*-Sichten, nicht aus INFORMATION_SCHEMA —
// letzteres kennt sie gar nicht.
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
                    stmt.execute("CREATE INDEX ix_orders_covering ON orders(customer_id) INCLUDE (state)")
                    // Ein clustered Index auf einer Nicht-PK-Spalte verlangt, dass der
                    // Primaerschluessel nonclustered ist — es gibt nur eine Ablage.
                    stmt.execute(
                        """
                        CREATE TABLE shipments (
                            id INT NOT NULL CONSTRAINT pk_shipments PRIMARY KEY NONCLUSTERED,
                            shipped_on DATE NOT NULL
                        )
                        """.trimIndent(),
                    )
                    stmt.execute("CREATE CLUSTERED INDEX ix_shipments_shipped ON shipments(shipped_on)")
                    // Ein Unique-Index, der die Ablage beansprucht: als
                    // UNIQUE-Constraint ist das nicht ausdrueckbar, gehoben ginge
                    // die Ablage-Aussage verloren.
                    stmt.execute(
                        """
                        CREATE TABLE tickets (
                            id INT NOT NULL CONSTRAINT pk_tickets PRIMARY KEY NONCLUSTERED,
                            code NVARCHAR(40) NOT NULL,
                            note NVARCHAR(80)
                        )
                        """.trimIndent(),
                    )
                    stmt.execute("CREATE UNIQUE CLUSTERED INDEX ux_tickets_code ON tickets(code)")
                    stmt.execute("CREATE UNIQUE INDEX ux_tickets_note ON tickets(note) INCLUDE (code)")
                    // RANGE RIGHT: der Grenzwert gehoert zur oberen Partition,
                    // also `[from, to)` — genau das, was das neutrale Modell sagt.
                    stmt.execute(
                        "CREATE PARTITION FUNCTION pf_events (INT) AS RANGE RIGHT FOR VALUES (100, 200)",
                    )
                    stmt.execute(
                        "CREATE PARTITION SCHEME ps_events AS PARTITION pf_events ALL TO ([PRIMARY])",
                    )
                    stmt.execute(
                        "CREATE TABLE events (id INT NOT NULL, payload NVARCHAR(50)) ON ps_events (id)",
                    )
                    // RANGE LEFT: nicht als halboffenes Intervall ausdrueckbar.
                    stmt.execute(
                        "CREATE PARTITION FUNCTION pf_legacy (INT) AS RANGE LEFT FOR VALUES (10)",
                    )
                    stmt.execute(
                        "CREATE PARTITION SCHEME ps_legacy AS PARTITION pf_legacy ALL TO ([PRIMARY])",
                    )
                    stmt.execute(
                        "CREATE TABLE legacy_events (id INT NOT NULL, payload NVARCHAR(50)) ON ps_legacy (id)",
                    )
                    // Datums-Grenzen sind der realistische Fall — und die Stelle,
                    // an der sich zeigt, ob das Literal dieselbe Form hat wie beim
                    // PostgreSQL-Reverse. `sql_variant` liefert je nach Treiber
                    // Timestamp, Date oder String.
                    stmt.execute(
                        "CREATE PARTITION FUNCTION pf_daily (DATE) AS RANGE RIGHT " +
                            "FOR VALUES ('2024-01-01', '2025-01-01')",
                    )
                    stmt.execute(
                        "CREATE PARTITION SCHEME ps_daily AS PARTITION pf_daily ALL TO ([PRIMARY])",
                    )
                    stmt.execute(
                        "CREATE TABLE daily_events (occurred_on DATE NOT NULL, note NVARCHAR(50)) " +
                            "ON ps_daily (occurred_on)",
                    )
                    // Die beiden Typen, bei denen der Review Drift gegen die
                    // PostgreSQL-Form vermutet — gemessen statt formatiert.
                    stmt.execute(
                        "CREATE PARTITION FUNCTION pf_stamped (DATETIME2(0)) AS RANGE RIGHT " +
                            "FOR VALUES ('2024-01-01T00:00:00')",
                    )
                    stmt.execute(
                        "CREATE PARTITION SCHEME ps_stamped AS PARTITION pf_stamped ALL TO ([PRIMARY])",
                    )
                    stmt.execute(
                        "CREATE TABLE stamped_events (seen_at DATETIME2(0) NOT NULL) ON ps_stamped (seen_at)",
                    )
                    stmt.execute(
                        "CREATE PARTITION FUNCTION pf_priced (DECIMAL(10,2)) AS RANGE RIGHT FOR VALUES (1.5)",
                    )
                    stmt.execute(
                        "CREATE PARTITION SCHEME ps_priced AS PARTITION pf_priced ALL TO ([PRIMARY])",
                    )
                    stmt.execute(
                        "CREATE TABLE priced_events (amount DECIMAL(10,2) NOT NULL) ON ps_priced (amount)",
                    )
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
            MssqlTableLister().listTables(pool) shouldBe
                listOf(
                    "customers", "daily_events", "events", "legacy_events", "orders",
                    "priced_events", "shipments", "stamped_events", "tickets",
                )
        }
    }

    test("a RANGE RIGHT partitioned table is read with half-open bounds and synthesized names") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val result = MssqlSchemaReader().read(pool)
            val partitioning = result.schema.tables.getValue("events").partitioning.shouldNotBeNull()

            partitioning.type shouldBe PartitionType.RANGE
            partitioning.key shouldBe listOf("id")
            // Zwei Grenzwerte ergeben drei Partitionen.
            partitioning.partitions.map { it.name } shouldBe listOf("p1", "p2", "p3")
            partitioning.partitions[0].from shouldBe listOf(PartitionBound.MinValue)
            partitioning.partitions[0].to shouldBe listOf(PartitionBound.Value("100"))
            partitioning.partitions[1].from shouldBe listOf(PartitionBound.Value("100"))
            partitioning.partitions[1].to shouldBe listOf(PartitionBound.Value("200"))
            partitioning.partitions[2].to shouldBe listOf(PartitionBound.MaxValue)

            // Der Namensverlust wird gemeldet, nicht verschwiegen.
            result.notes.first { it.code == "R346" }.message strShouldContain "events"
        }
    }

    test("a DATE boundary reads as the same literal shape PostgreSQL produces") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val partitioning = MssqlSchemaReader().read(pool)
                .schema.tables.getValue("daily_events").partitioning.shouldNotBeNull()

            // Der PostgreSQL-Reverse liefert Datumsgrenzen als `'2024-01-01'`.
            // Kaeme hier `'2024-01-01 00:00:00.0'` heraus, meldete ein
            // Cross-Dialekt-Vergleich Drift auf identischen Schemata.
            partitioning.partitions[0].to shouldBe listOf(PartitionBound.Value("'2024-01-01'"))
            partitioning.partitions[1].from shouldBe listOf(PartitionBound.Value("'2024-01-01'"))
            partitioning.partitions[1].to shouldBe listOf(PartitionBound.Value("'2025-01-01'"))
        }
    }

    test("datetime2 and decimal boundaries take the same shape PostgreSQL produces") {
        // Beide Formen sind gegen echte Server gemessen, auf beiden Seiten:
        //
        // - `datetime2` kam als `'2024-01-01 00:00:00.0'` zurueck, weil
        //   `java.sql.Timestamp.toString()` immer eine Nachkommastelle anhaengt.
        //   PostgreSQL schreibt sie nie — das war echte Drift und ist behoben.
        // - `decimal(10,2)` kommt als `1.50`, und PostgreSQL liefert fuer
        //   `numeric(10,2)` ebenfalls `1.50`. Die beiden stimmen ueberein; eine
        //   Normalisierung auf `1.5` haette die Drift erst erzeugt.
        HikariConnectionPoolFactory.create(config).use { pool ->
            val schema = MssqlSchemaReader().read(pool).schema
            val stamped = schema.tables.getValue("stamped_events").partitioning.shouldNotBeNull()
            val priced = schema.tables.getValue("priced_events").partitioning.shouldNotBeNull()

            withClue("datetime2(0)-Grenze: ${stamped.partitions[0].to}") {
                stamped.partitions[0].to shouldBe listOf(PartitionBound.Value("'2024-01-01 00:00:00'"))
            }
            withClue("decimal(10,2)-Grenze: ${priced.partitions[0].to}") {
                priced.partitions[0].to shouldBe listOf(PartitionBound.Value("1.50"))
            }
        }
    }

    test("a RANGE LEFT partitioned table keeps the partitioning fact, with R347") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val result = MssqlSchemaReader().read(pool)
            // Lieber unpartitioniert und laut, als Grenzen, die beim
            // Regenerieren andere Zeilen routen.
            // Ohne Kinder, aber NICHT null: sonst waere der Rebuild-Waechter blind,
            // der eine partitionierte Tabelle vor dem Neubau schuetzt.
            val partitioning = result.schema.tables.getValue("legacy_events").partitioning.shouldNotBeNull()
            partitioning.partitions.shouldBeEmpty()
            val note = result.notes.first { it.code == "R347" }
            note.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
            note.message strShouldContain "RANGE LEFT"
        }
    }

    test("a unique index that carries storage or INCLUDE stays an index, not a constraint") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val tickets = MssqlSchemaReader().read(pool).schema.tables.getValue("tickets")

            // Gehoben waere beides zu einem UNIQUE-Constraint geworden — und die
            // Ablage bzw. die eingeschlossene Spalte waere still verschwunden.
            val storage = tickets.indices.first { it.name == "ux_tickets_code" }
            storage.unique shouldBe true
            storage.clustered shouldBe true

            val covering = tickets.indices.first { it.name == "ux_tickets_note" }
            covering.unique shouldBe true
            covering.includeColumns shouldBe listOf("code")

            // Und sie stehen NICHT zusaetzlich als Constraint da.
            tickets.constraints.map { it.name } shouldNotContain "ux_tickets_code"
            tickets.constraints.map { it.name } shouldNotContain "ux_tickets_note"
            // `code` ist unique, aber nicht ueber die Spaltenfahne — sonst waere die
            // Aussage doppelt und der Generate-Pfad legte sie zweimal an.
            tickets.columns.getValue("code").unique shouldBe false
        }
    }

    test("reverse reads INCLUDE columns and the clustered index from the catalog") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val schema = MssqlSchemaReader().read(pool).schema

            val covering = schema.tables.getValue("orders").indices.first { it.name == "ix_orders_covering" }
            // Die eingeschlossene Spalte steht NEBEN dem Schluessel. Haenge sie der
            // Katalog an die Schluesselspalten, waere aus dem abdeckenden Index ein
            // zusammengesetzter geworden — bei `unique` mit anderer Semantik.
            covering.columns.map { it.name } shouldBe listOf("customer_id")
            covering.includeColumns shouldBe listOf("state")
            covering.clustered shouldBe false

            val storage = schema.tables.getValue("shipments").indices.first { it.name == "ix_shipments_shipped" }
            storage.clustered shouldBe true

            // Der gefilterte Index traegt keine der beiden Eigenschaften — sonst
            // liesse sich nicht unterscheiden, ob der Reverse liest oder raet.
            val filtered = schema.tables.getValue("orders").indices.first { it.name == "ix_orders_state_open" }
            filtered.includeColumns shouldBe emptyList()
            filtered.clustered shouldBe false
        }
    }
})
