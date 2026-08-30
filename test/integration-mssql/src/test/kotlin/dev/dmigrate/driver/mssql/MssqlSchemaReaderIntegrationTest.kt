package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.PartitionBound
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MssqlHashPartitionMode
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
import io.kotest.matchers.string.shouldNotContain as strShouldNotContain
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
                    // Die beiden Typen, deren Grenzliteral gegen die
                    // PostgreSQL-Form driften kann.
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

    test("schema reverse reads tables, sequences, views and routines") {
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

            // Der Rumpf wird gelesen; `R342` bleibt fuer das, was wirklich
            // keinen T-SQL-Rumpf hat (CLR, WITH ENCRYPTION).
            result.schema.procedures.getValue("usp_noop()").body.shouldNotBeNull() strShouldContain "SELECT"
            result.skippedObjects.map { it.name } shouldNotContain "usp_noop"
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
        // Beide Formen, wie die Server sie liefern:
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

    test("generated partition DDL applies, and a clustered index keeps the partitioning readable") {
        // Der Round-Trip in beide Richtungen: erzeugtes DDL anwenden, dann
        // zurueckleben. Deckt zugleich ab, dass eine Tabelle mit CLUSTERED
        // Index (statt Heap) ihre Partitionierungsspalte ueber
        // `partition_ordinal` findet.
        HikariConnectionPoolFactory.create(config).use { pool ->
            val desired = SchemaDefinition(
                name = "gen", version = "1",
                tables = mapOf(
                    "generated_parts" to TableDefinition(
                        columns = linkedMapOf(
                            "bucket" to ColumnDefinition(NeutralType.Integer, required = true),
                            "payload" to ColumnDefinition(NeutralType.Text(maxLength = 30)),
                        ),
                        primaryKey = listOf("bucket"),
                        partitioning = PartitionConfig(
                            type = PartitionType.RANGE,
                            key = listOf("bucket"),
                            partitions = listOf(
                                PartitionDefinition(
                                    name = "p1",
                                    from = listOf(PartitionBound.MinValue),
                                    to = listOf(PartitionBound.Value("100")),
                                ),
                                PartitionDefinition(
                                    name = "p2",
                                    from = listOf(PartitionBound.Value("100")),
                                    to = listOf(PartitionBound.MaxValue),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val ddl = MssqlDdlGenerator().generate(desired).render()
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        // Anwenden, wie es erzeugt wurde — der Server ist der Richter.
                        ddl.lines()
                            .filter { it.isNotBlank() && !it.trimStart().startsWith("--") }
                            .joinToString("\n")
                            .split(";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { stmt.execute(it) }
                    }
                }

                val readBack = MssqlSchemaReader().read(pool)
                    .schema.tables.getValue("generated_parts").partitioning.shouldNotBeNull()
                readBack.key shouldBe listOf("bucket")
                readBack.partitions.map { it.to } shouldBe listOf(
                    listOf(PartitionBound.Value("100")),
                    listOf(PartitionBound.MaxValue),
                )
                // Der Primaerschluessel macht daraus einen CLUSTERED Index, keinen Heap —
                // damit ist der Pfad belegt, den die bisherigen Heap-Tabellen offenliessen.
                val storageForm = pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT type_desc FROM sys.indexes " +
                                "WHERE object_id = OBJECT_ID('generated_parts') AND index_id = 1",
                        ).use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                }
                storageForm shouldBe "CLUSTERED"
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DROP TABLE IF EXISTS generated_parts")
                        stmt.execute("IF EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'ps_generated_parts') " +
                            "DROP PARTITION SCHEME ps_generated_parts")
                        stmt.execute("IF EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'pf_generated_parts') " +
                            "DROP PARTITION FUNCTION pf_generated_parts")
                    }
                }
            }
        }
    }

    test("a generated function with a temporal key is accepted by the server") {
        // Die N-Praefix-Regel lautet "einfach gequotetes Literal -> N davor", und
        // Datumsgrenzen traegt das Modell als Zeichenkette. Ob SQL Server ein
        // N'…' als Grenzwert einer DATETIMEOFFSET-Funktion annimmt, ist damit
        // eine Annahme — und das DDL-Golden schreibt sie fest, ohne sie je gegen
        // einen Server zu halten. Hier wird sie gehalten.
        HikariConnectionPoolFactory.create(config).use { pool ->
            val desired = SchemaDefinition(
                name = "temporal", version = "1",
                tables = mapOf(
                    "temporal_parts" to TableDefinition(
                        columns = linkedMapOf(
                            "seen_at" to ColumnDefinition(NeutralType.DateTime(timezone = true), required = true),
                            "note" to ColumnDefinition(NeutralType.Text(maxLength = 20)),
                        ),
                        partitioning = PartitionConfig(
                            type = PartitionType.RANGE,
                            key = listOf("seen_at"),
                            partitions = listOf(
                                PartitionDefinition(
                                    name = "p1",
                                    from = listOf(PartitionBound.MinValue),
                                    to = listOf(PartitionBound.Value("'2025-01-01'")),
                                ),
                                PartitionDefinition(
                                    name = "p2",
                                    from = listOf(PartitionBound.Value("'2025-01-01'")),
                                    to = listOf(PartitionBound.MaxValue),
                                ),
                            ),
                        ),
                    ),
                ),
            )
            val ddl = MssqlDdlGenerator().generate(desired).render()
            withClue("erzeugt:\n$ddl") {
                ddl strShouldContain "N'2025-01-01'"
            }
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        ddl.lines()
                            .filter { it.isNotBlank() && !it.trimStart().startsWith("--") }
                            .joinToString("\n")
                            .split(";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { stmt.execute(it) }
                    }
                }
                // Angenommen — und die Grenze steht so im Katalog, wie sie gemeint war.
                val readBack = MssqlSchemaReader().read(pool)
                    .schema.tables.getValue("temporal_parts").partitioning.shouldNotBeNull()
                readBack.partitions.size shouldBe 2
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DROP TABLE IF EXISTS temporal_parts")
                        stmt.execute("IF EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'ps_temporal_parts') " +
                            "DROP PARTITION SCHEME ps_temporal_parts")
                        stmt.execute("IF EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'pf_temporal_parts') " +
                            "DROP PARTITION FUNCTION pf_temporal_parts")
                    }
                }
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

    // Die HASH-Emulation gegen den echten Server. Der Beleg ist
    // nicht das DDL, sondern dass der Server es annimmt UND die Zeilen sich
    // wirklich auf die Eimer verteilen — eine Emulation, die alles in eine
    // Partition legte, waere gueltiges DDL und trotzdem wertlos.
    test("the hash emulation is accepted and actually distributes rows") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val desired = SchemaDefinition(
                name = "hash", version = "1",
                tables = mapOf(
                    "hash_events" to TableDefinition(
                        columns = linkedMapOf(
                            "id" to ColumnDefinition(NeutralType.Integer, required = true),
                            "customer_id" to ColumnDefinition(NeutralType.Integer, required = true),
                        ),
                        primaryKey = listOf("id", "customer_id"),
                        partitioning = PartitionConfig(
                            type = PartitionType.HASH,
                            key = listOf("customer_id"),
                            partitions = (0 until 4).map {
                                PartitionDefinition(name = "p$it", modulus = 4, remainder = it)
                            },
                        ),
                    ),
                ),
            )
            val ddl = MssqlDdlGenerator().generate(
                desired,
                DdlGenerationOptions(
                    dialectContext = DdlDialectContext.MsSql(
                        hashPartitionMode = MssqlHashPartitionMode.COMPUTED_COLUMN,
                    ),
                ),
            ).render()
            withClue("erzeugt:\n$ddl") { ddl strShouldContain "PERSISTED" }

            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        ddl.lines()
                            .filter { it.isNotBlank() && !it.trimStart().startsWith("--") }
                            .joinToString("\n")
                            .split(";")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .forEach { stmt.execute(it) }
                        stmt.execute(
                            "INSERT INTO hash_events (id, customer_id) VALUES " +
                                "(1,10),(2,11),(3,12),(4,13),(5,14),(6,15),(7,16),(8,17)",
                        )
                    }
                }
                val populated = pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT COUNT(*) FROM sys.partitions WHERE object_id = OBJECT_ID('hash_events') " +
                                "AND index_id IN (0,1) AND rows > 0",
                        ).use { rs -> rs.next(); rs.getInt(1) }
                    }
                }
                withClue("acht Zeilen ueber vier Eimer fuellen mehr als eine Partition") {
                    (populated > 1) shouldBe true
                }
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DROP TABLE IF EXISTS hash_events")
                        stmt.execute("IF EXISTS (SELECT 1 FROM sys.partition_schemes WHERE name = 'ps_hash_events') " +
                            "DROP PARTITION SCHEME ps_hash_events")
                        stmt.execute("IF EXISTS (SELECT 1 FROM sys.partition_functions WHERE name = 'pf_hash_events') " +
                            "DROP PARTITION FUNCTION pf_hash_events")
                    }
                }
            }
        }
    }

    // Der Generate-Pfad rendert die spaltenstaendige `references`-Form, der
    // Migrate-Pfad tat es nicht: die Tabelle entstand, die Beziehung fehlte —
    // ohne Fehlschlag, sichtbar erst im Postcompare.
    test("a column-level reference survives schema migrate") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            try {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("CREATE TABLE fk_parent (id INT NOT NULL PRIMARY KEY)")
                    }
                }
                val current = MssqlSchemaReader().read(pool).schema
                val child = TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "parent_id" to ColumnDefinition(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "fk_parent", column = "id"),
                        ),
                    ),
                    primaryKey = listOf("id"),
                )
                val desired = current.copy(tables = current.tables + ("fk_child" to child))
                val diff = SchemaComparator().compare(current, desired)
                val plan = DiffPlanner().plan(current, desired, diff)
                val migration = MssqlDiffDdlGenerator().generateUp(plan, DdlGenerationOptions())
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        migration.statements.forEach { stmt.execute(it.sql) }
                    }
                }

                // Der Server fuehrt die Beziehung — nicht nur das Modell.
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(
                            "SELECT name FROM sys.foreign_keys WHERE parent_object_id = OBJECT_ID('fk_child')",
                        ).use { rs ->
                            rs.next() shouldBe true
                            rs.getString(1) shouldBe "fk_fk_child_parent_id"
                        }
                    }
                }
            } finally {
                pool.borrow().asJdbc().use { conn ->
                    conn.createStatement().use { stmt ->
                        runCatching { stmt.execute("DROP TABLE fk_child") }
                        runCatching { stmt.execute("DROP TABLE fk_parent") }
                    }
                }
            }
        }
    }
})
