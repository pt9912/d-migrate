package dev.dmigrate.driver.mysql

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.identity.ReverseScopeCodec
import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.DatabaseDriverRegistry
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.nulls.shouldNotBeNull
import org.testcontainers.mysql.MySQLContainer

private val IntegrationTag = NamedTag("integration")

class MysqlSchemaReaderIntegrationTest : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    val reader = MysqlSchemaReader()

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MysqlDriver())

        val config = ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_test",
            user = "dmigrate",
            password = "dmigrate",
        )
        val pool = HikariConnectionPoolFactory.create(config)
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE customers (
                        id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        name VARCHAR(100) NOT NULL,
                        email VARCHAR(254) UNIQUE,
                        score DECIMAL(5,2) DEFAULT 0.00,
                        data JSON,
                        active TINYINT(1) DEFAULT 1,
                        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB
                """)

                stmt.execute("""
                    CREATE TABLE orders (
                        id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                        customer_id INT NOT NULL,
                        status ENUM('pending', 'shipped', 'delivered') NOT NULL DEFAULT 'pending',
                        total DECIMAL(10,2),
                        tags SET('urgent','bulk','vip'),
                        CONSTRAINT fk_customer FOREIGN KEY (customer_id) REFERENCES customers(id) ON DELETE CASCADE
                    ) ENGINE=InnoDB
                """)

                stmt.execute("CREATE INDEX idx_orders_customer ON orders (customer_id)")

                stmt.execute("""
                    CREATE VIEW active_orders AS
                    SELECT o.*, c.name AS customer_name
                    FROM orders o JOIN customers c ON o.customer_id = c.id
                """)

                // Function
                stmt.execute("""
                    CREATE FUNCTION get_name(p_id INT) RETURNS VARCHAR(100)
                    DETERMINISTIC
                    READS SQL DATA
                    RETURN (SELECT name FROM customers WHERE id = p_id)
                """)

                // Procedure
                stmt.execute("""
                    CREATE PROCEDURE reset_score(IN p_id INT)
                    BEGIN
                        UPDATE customers SET score = 0 WHERE id = p_id;
                    END
                """)

                stmt.execute("""
                    CREATE TRIGGER trg_before_insert
                    BEFORE INSERT ON customers
                    FOR EACH ROW
                    SET NEW.created_at = NOW()
                """)
            }
        }
        pool.close()
    }

    afterSpec { container.stop() }

    fun pool() = HikariConnectionPoolFactory.create(
        ConnectionConfig(
            dialect = DatabaseDialect.MYSQL,
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
            val scope = ReverseScopeCodec.parseScope(result.schema.name)
            scope["dialect"] shouldBe "mysql"
            scope["database"] shouldBe "dmigrate_test"
        }
    }

    // ── Tables with engine ──────────────────────

    test("reads tables with engine metadata") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables shouldContainKey "customers"
            result.schema.tables shouldContainKey "orders"

            result.schema.tables["customers"]!!.metadata shouldNotBe null
            result.schema.tables["customers"]!!.metadata!!.engine shouldBe "InnoDB"
        }
    }

    // ── Column types ────────────────────────────

    test("reads column types correctly") {
        pool().use { pool ->
            val result = reader.read(pool)
            val c = result.schema.tables["customers"]!!

            // INT AUTO_INCREMENT → Identifier
            c.columns["id"]!!.type shouldBe NeutralType.Identifier(autoIncrement = true)

            // VARCHAR(100) NOT NULL
            c.columns["name"]!!.type shouldBe NeutralType.Text(maxLength = 100)
            c.columns["name"]!!.required shouldBe true

            // JSON
            c.columns["data"]!!.type shouldBe NeutralType.Json

            // TINYINT(1) → Boolean
            c.columns["active"]!!.type shouldBe NeutralType.BooleanType

            // DATETIME
            c.columns["created_at"]!!.type shouldBe NeutralType.DateTime()
        }
    }

    // ── Bigint AUTO_INCREMENT → BigInteger ──────

    test("bigint auto_increment preserves BigInteger type width") {
        pool().use { pool ->
            val result = reader.read(pool)
            val orderId = result.schema.tables["orders"]!!.columns["id"]!!
            orderId.type shouldBe NeutralType.BigInteger
            result.notes.any { it.code == "R300" && it.objectName.contains("orders.id") } shouldBe true
        }
    }

    // ── PK-implicit required/unique not duplicated ──

    test("PK columns do not have redundant required or unique") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables["customers"]!!.columns["id"]!!.required shouldBe false
            result.schema.tables["customers"]!!.columns["id"]!!.unique shouldBe false
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

    // ── ENUM column ─────────────────────────────

    test("enum column maps to Enum with values") {
        pool().use { pool ->
            val result = reader.read(pool)
            val statusCol = result.schema.tables["orders"]!!.columns["status"]!!
            val enumType = statusCol.type as NeutralType.Enum
            enumType.values shouldBe listOf("pending", "shipped", "delivered")
        }
    }

    // ── SET column → Note ───────────────────────

    test("SET type produces ACTION_REQUIRED note") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables["orders"]!!.columns["tags"]!!.type shouldBe NeutralType.Text()
            result.notes.any {
                it.code == "R320" && it.severity == SchemaReadSeverity.ACTION_REQUIRED
            } shouldBe true
        }
    }

    // ── Views ───────────────────────────────────

    test("reads views when includeViews is true") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeViews = true))
            result.schema.views shouldContainKey "active_orders"
        }
    }

    test("skips views when includeViews is false") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeViews = false))
            result.schema.views.size shouldBe 0
        }
    }

    // ── Triggers ────────────────────────────────

    test("reads triggers with canonical keys") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeTriggers = true))
            val expectedKey = ObjectKeyCodec.triggerKey("customers", "trg_before_insert")
            result.schema.triggers shouldContainKey expectedKey
            val trg = result.schema.triggers[expectedKey]!!
            trg.table shouldBe "customers"
            trg.timing shouldBe TriggerTiming.BEFORE
            trg.event shouldBe TriggerEvent.INSERT
        }
    }

    // ── User indices preserved ──────────────────

    test("user-created indices are preserved") {
        pool().use { pool ->
            val result = reader.read(pool)
            val orders = result.schema.tables["orders"]!!
            orders.indices.any { it.name == "idx_orders_customer" } shouldBe true
        }
    }

    // ── DECIMAL ─────────────────────────────────

    test("decimal columns map correctly") {
        pool().use { pool ->
            val result = reader.read(pool)
            result.schema.tables["customers"]!!.columns["score"]!!.type shouldBe
                NeutralType.Decimal(precision = 5, scale = 2)
        }
    }

    // ── Functions ───────────────────────────────

    test("reads functions with canonical keys") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeFunctions = true))
            val fnKeys = result.schema.functions.keys
            fnKeys.any { it.startsWith("get_name(") } shouldBe true
        }
    }

    test("skips functions when includeFunctions is false") {
        pool().use { pool ->
            val result = reader.read(pool, SchemaReadOptions(includeFunctions = false))
            result.schema.functions.size shouldBe 0
        }
    }

    // ── Procedures ──────────────────────────────

    test("reads procedures with canonical keys") {
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
})
