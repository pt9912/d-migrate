package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.testcontainers.postgresql.PostgreSQLContainer

private val IntegrationTag = NamedTag("integration")

class PostgresSchemaReaderIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val reader = PostgresSchemaReader()

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())

        val config = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_test",
            user = "dmigrate",
            password = "dmigrate",
        )
        val pool = HikariConnectionPoolFactory.create(config)
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                // Extension (covers SchemaReader extension note loop)
                stmt.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"")

                // Enum type
                stmt.execute("CREATE TYPE order_status AS ENUM ('pending', 'shipped', 'delivered')")

                // Sequence
                stmt.execute("CREATE SEQUENCE invoice_seq START 1000 INCREMENT 1")

                // Tables
                stmt.execute("""
                    CREATE TABLE customers (
                        id SERIAL PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(254) UNIQUE,
                        score NUMERIC(5,2) DEFAULT 0.0,
                        metadata JSONB,
                        uid UUID DEFAULT gen_random_uuid(),
                        created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
                    )
                """)

                stmt.execute("""
                    CREATE TABLE orders (
                        id BIGSERIAL PRIMARY KEY,
                        customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
                        status order_status NOT NULL DEFAULT 'pending',
                        total NUMERIC(10,2),
                        notes TEXT
                    )
                """)

                stmt.execute("CREATE INDEX idx_orders_customer ON orders (customer_id)")
                stmt.execute("CREATE INDEX idx_orders_status ON orders USING hash (status)")

                // View
                stmt.execute("""
                    CREATE VIEW active_orders AS
                    SELECT o.*, c.name AS customer_name
                    FROM orders o JOIN customers c ON o.customer_id = c.id
                """)

                // Function
                stmt.execute("""
                    CREATE FUNCTION get_customer_name(p_id INTEGER) RETURNS TEXT AS $$
                    BEGIN
                        RETURN (SELECT name FROM customers WHERE id = p_id);
                    END;
                    $$ LANGUAGE plpgsql
                """)

                // Domain type
                stmt.execute("CREATE DOMAIN positive_int AS INTEGER CHECK (VALUE > 0)")

                // Composite type
                stmt.execute("CREATE TYPE address AS (street TEXT, city VARCHAR(100))")

                // Overloaded function
                stmt.execute("""
                    CREATE FUNCTION calc(x INTEGER) RETURNS INTEGER AS $$
                    BEGIN RETURN x * 2; END;
                    $$ LANGUAGE plpgsql
                """)
                stmt.execute("""
                    CREATE FUNCTION calc(x INTEGER, y INTEGER) RETURNS INTEGER AS $$
                    BEGIN RETURN x + y; END;
                    $$ LANGUAGE plpgsql
                """)

                // Procedure (PG 14+)
                stmt.execute("""
                    CREATE PROCEDURE reset_score(p_id INTEGER)
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        UPDATE customers SET score = 0 WHERE id = p_id;
                    END;
                    $$
                """)

                // Trigger
                stmt.execute("""
                    CREATE FUNCTION trg_fn() RETURNS TRIGGER AS $$
                    BEGIN RETURN NEW; END;
                    $$ LANGUAGE plpgsql
                """)
                stmt.execute("""
                    CREATE TRIGGER trg_before_insert
                    BEFORE INSERT ON customers
                    FOR EACH ROW EXECUTE FUNCTION trg_fn()
                """)
            }
        }
        pool.close()
    }

    afterSpec { container.stop() }

    fun pool() = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_test",
            user = "dmigrate",
            password = "dmigrate",
        )
    )

    // ── Canonical name/version ──────────────────

    test("reverse name and version follow canonical format") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.version shouldBe ReverseScopeCodec.REVERSE_VERSION
            result.schema.name.startsWith(ReverseScopeCodec.PREFIX) shouldBe true
            val scope = ReverseScopeCodec.parseScope(result.schema.name)
            scope["dialect"] shouldBe "postgresql"
            scope["database"] shouldBe "dmigrate_test"
            scope["schema"] shouldNotBe null
        }
    }

    // ── Tables ──────────────────────────────────

    test("reads tables with columns and types") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables shouldContainKey "customers"
            result.schema.tables shouldContainKey "orders"

            val customers = result.schema.tables["customers"]!!
            customers.primaryKey shouldBe listOf("id")

            // Serial → Identifier(autoIncrement)
            customers.columns["id"]!!.type shouldBe NeutralType.Identifier(autoIncrement = true)

            // VARCHAR(100) NOT NULL
            customers.columns["name"]!!.type shouldBe NeutralType.Text(maxLength = 100)
            customers.columns["name"]!!.required shouldBe true

            // JSONB → Json
            customers.columns["metadata"]!!.type shouldBe NeutralType.Json

            // UUID
            customers.columns["uid"]!!.type shouldBe NeutralType.Uuid

            // TIMESTAMPTZ
            customers.columns["created_at"]!!.type shouldBe NeutralType.DateTime(timezone = true)
        }
    }

    // ── PK-implicit required/unique not duplicated ──

    test("PK columns do not have redundant required or unique") {
        pool().use { pool ->
            val result = reader.read(pool)
            val customers = result.schema.tables["customers"]!!
            customers.columns["id"]!!.required shouldBe false
            customers.columns["id"]!!.unique shouldBe false
        }
    }

    // ── Single-column UNIQUE lifted ─────────────

    test("single-column UNIQUE lifted to ColumnDefinition.unique") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables["customers"]!!.columns["email"]!!.unique shouldBe true
        }
    }

    // ── Single-column FK lifted ─────────────────

    test("single-column FK lifted to ColumnDefinition.references") {
        pool().use { pool ->
            val result = reader.read(pool)
            val ref = result.schema.tables["orders"]!!.columns["customer_id"]!!.references
            ref.shouldNotBeNull()
            ref.table shouldBe "customers"
            ref.column shouldBe "id"
            ref.onDelete shouldBe ReferentialAction.CASCADE
        }
    }

    // ── Bigserial → BigInteger (not Identifier) ──

    test("bigserial preserves BigInteger type width") {
        pool().use { pool ->
            val result = reader.read(pool)
            val orderId = result.schema.tables["orders"]!!.columns["id"]!!
            orderId.type shouldBe NeutralType.BigInteger
            // Note should document the AI property
            result.notes.any { it.code == "R300" && it.objectName.contains("orders.id") } shouldBe true
        }
    }

    // ── Backing-index suppression ───────────────

    test("PK and UNIQUE constraint backing indices are suppressed") {
        pool().use { pool ->
            val result = reader.read(pool)
            val customers = result.schema.tables["customers"]!!
            // Should NOT contain the PK index or the UNIQUE index for email
            val indexNames = customers.indices.mapNotNull { it.name }
            indexNames.none { it.contains("pkey") } shouldBe true
        }
    }

    // ── User-created indices preserved ──────────

    test("user-created indices are preserved") {
        pool().use { pool ->
            val result = reader.read(pool)
            val orders = result.schema.tables["orders"]!!
            orders.indices.any { it.name == "idx_orders_customer" } shouldBe true
            orders.indices.any { it.name == "idx_orders_status" } shouldBe true
        }
    }

    // ── Enum types ──────────────────────────────

    test("reads ENUM custom types") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.customTypes shouldContainKey "order_status"
            val enumType = result.schema.customTypes["order_status"]!!
            enumType.kind shouldBe CustomTypeKind.ENUM
            enumType.values shouldBe listOf("pending", "shipped", "delivered")
        }
    }

    // ── Enum column reference ───────────────────

    test("enum column uses refType") {
        pool().use { pool ->
            val result = reader.read(pool)
            val statusCol = result.schema.tables["orders"]!!.columns["status"]!!
            val enumType = statusCol.type as NeutralType.Enum
            enumType.refType shouldBe "order_status"
        }
    }

    // ── Sequences ───────────────────────────────

    test("reads explicit sequences") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.sequences shouldContainKey "invoice_seq"
            val seq = result.schema.sequences["invoice_seq"]!!
            seq.start shouldBe 1000
            seq.increment shouldBe 1
        }
    }

    // ── Views ───────────────────────────────────

    test("reads views when includeViews is true") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeViews = true))
            result.schema.views shouldContainKey "active_orders"
            result.schema.views["active_orders"]!!.sourceDialect shouldBe "postgresql"
        }
    }

    test("skips views when includeViews is false") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeViews = false))
            result.schema.views.size shouldBe 0
        }
    }

    // ── Functions ───────────────────────────────

    test("reads functions with canonical keys when includeFunctions is true") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeFunctions = true))
            // Should contain get_customer_name with canonical key
            val fnKeys = result.schema.functions.keys
            fnKeys.any { it.startsWith("get_customer_name(") } shouldBe true
        }
    }

    test("skips functions when includeFunctions is false") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeFunctions = false))
            result.schema.functions.size shouldBe 0
        }
    }

    // ── Procedures ──────────────────────────────

    test("reads procedures with canonical keys when includeProcedures is true") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeProcedures = true))
            val procKeys = result.schema.procedures.keys
            procKeys.any { it.startsWith("reset_score(") } shouldBe true
        }
    }

    test("skips procedures when includeProcedures is false") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeProcedures = false))
            result.schema.procedures.size shouldBe 0
        }
    }

    // ── Triggers ────────────────────────────────

    test("reads triggers with canonical keys when includeTriggers is true") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeTriggers = true))
            val triggerKeys = result.schema.triggers.keys
            val expectedKey = ObjectKeyCodec.triggerKey("customers", "trg_before_insert")
            triggerKeys.any { it == expectedKey } shouldBe true
            val trg = result.schema.triggers[expectedKey]!!
            trg.table shouldBe "customers"
            trg.timing shouldBe TriggerTiming.BEFORE
            trg.event shouldBe TriggerEvent.INSERT
        }
    }

    test("skips triggers when includeTriggers is false") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeTriggers = false))
            result.schema.triggers.size shouldBe 0
        }
    }

    // ── NUMERIC(p,s) ────────────────────────────

    test("numeric columns map to Decimal") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables["customers"]!!.columns["score"]!!.type shouldBe
                NeutralType.Decimal(precision = 5, scale = 2)
        }
    }

    // ── DOMAIN custom type ──────────────────────

    test("reads DOMAIN custom types") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.customTypes shouldContainKey "positive_int"
            val domain = result.schema.customTypes["positive_int"]!!
            domain.kind shouldBe CustomTypeKind.DOMAIN
            domain.baseType shouldBe "integer"
            domain.check shouldNotBe null
        }
    }

    // ── COMPOSITE custom type ───────────────────

    test("reads COMPOSITE custom types") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.customTypes shouldContainKey "address"
            val comp = result.schema.customTypes["address"]!!
            comp.kind shouldBe CustomTypeKind.COMPOSITE
            comp.fields shouldNotBe null
            comp.fields!!.keys shouldBe setOf("street", "city")
        }
    }

    // ── Overloaded functions ────────────────────

    test("overloaded functions produce distinct canonical keys") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeFunctions = true))
            val calcKeys = result.schema.functions.keys.filter {
                ObjectKeyCodec.parseRoutineKey(it).first == "calc"
            }
            calcKeys.size shouldBe 2
            val paramCounts = calcKeys.map { ObjectKeyCodec.parseRoutineKey(it).second.size }.sorted()
            paramCounts shouldBe listOf(1, 2)
        }
    }

    // ── Reverse scope encoding ──────────────────

    test("reverse scope is parseable with correct components") {
        pool().use { pool ->
            val result = reader.read(pool)
            val scope = ReverseScopeCodec.parseScope(result.schema.name)
            scope["dialect"] shouldBe "postgresql"
            scope["database"] shouldBe "dmigrate_test"
            scope["schema"] shouldNotBe null
        }
    }

    test("reverse scope with structural separators round-trips correctly") {
        // Testcontainers DB name is fixed, but verify codec handles
        // hypothetical separator characters in db/schema names
        val encoded = ReverseScopeCodec.postgresName("my;db=1", "sch:ema%2")
        val scope = ReverseScopeCodec.parseScope(encoded)
        scope["database"] shouldBe "my;db=1"
        scope["schema"] shouldBe "sch:ema%2"
        ReverseScopeCodec.isReverseGenerated(encoded, ReverseScopeCodec.REVERSE_VERSION) shouldBe true
    }

    // ── Extension notes ─────────────────────────

    test("installed extensions produce INFO notes") {
        pool().use { pool ->
            val result = reader.read(pool)
            // uuid-ossp is installed in beforeSpec; plpgsql is excluded
            val extNotes = result.notes.filter { it.code == "R400" }
            extNotes.any { it.objectName == "uuid-ossp" } shouldBe true
            extNotes.forEach {
                it.severity shouldBe dev.dmigrate.driver.SchemaReadSeverity.INFO
            }
        }
    }

    // ── Ownership: pool reusable after read ─────

    test("pool is reusable after read") {
        pool().use { pool ->
            reader.read(pool)
            // Second read should work — connection was returned
            val result2 = reader.read(pool)
            result2.schema.tables shouldContainKey "customers"
        }
    }
})
