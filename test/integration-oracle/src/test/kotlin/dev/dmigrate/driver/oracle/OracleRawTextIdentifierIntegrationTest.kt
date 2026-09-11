package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.sql.SQLException
import java.time.Duration

/**
 * Roher SQL-Text (CHECK-Ausdruck, Sichten-Rumpf) gegen ein ECHTES Oracle.
 *
 * d-migrate legt Tabellen und Spalten wortgetreu gequotet an, also klein
 * geschrieben. Oracle faltet einen **unquotierten** Bezeichner dagegen auf
 * GROSSSCHREIBUNG — als einzige der fuenf Ziel-Engines. Ein durchgereichtes
 * `CHECK (total_amount >= 0)` sucht dort `TOTAL_AMOUNT` und findet
 * `"total_amount"` nicht.
 *
 * Was kein Unit-Test zeigen kann: dass das erzeugte Skript wirklich laeuft.
 * Die Goldens des Slice 2 sahen richtig aus und waren gegen eine echte
 * Instanz nicht anwendbar — niemand hatte sie je angewendet.
 */
class OracleRawTextIdentifierIntegrationTest : FunSpec({

    val container = OracleContainer(TestImages.ORACLE)
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(vararg sqls: String) = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
    }

    val schema = SchemaDefinition(
        name = "raw", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, ordinal = 1),
                    "total_amount" to ColumnDefinition(NeutralType.Decimal(10, 2), ordinal = 2),
                    "status" to ColumnDefinition(NeutralType.Text(maxLength = 20), ordinal = 3),
                ),
                primaryKey = listOf("id"),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "chk_total_positive",
                        type = ConstraintType.CHECK,
                        // Unquotiert, wie ein Anwender es schreibt und wie es
                        // aus einem PostgreSQL-Reverse kommt.
                        expression = "total_amount >= 0",
                    ),
                ),
            ),
        ),
        views = mapOf(
            "open_orders" to ViewDefinition(
                query = "SELECT * FROM orders WHERE status != 'delivered'",
                sourceDialect = "oracle",
            ),
        ),
    )

    test("the generated DDL applies against a real Oracle") {
        exec(
            "BEGIN EXECUTE IMMEDIATE 'DROP VIEW \"open_orders\"'; EXCEPTION WHEN OTHERS THEN NULL; END;",
            "BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"orders\" CASCADE CONSTRAINTS PURGE'; " +
                "EXCEPTION WHEN OTHERS THEN NULL; END;",
        )
        val generated = OracleDdlGenerator().generate(schema)

        // Der Beleg im Text: der Ausdruck ist gequotet, das Stringliteral nicht.
        val rendered = generated.render()
        rendered shouldContain "CHECK (\"total_amount\" >= 0)"
        rendered shouldContain "SELECT * FROM \"orders\" WHERE \"status\" != 'delivered'"

        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                generated.statements
                    .map { it.sql.lines().filterNot { line -> line.trimStart().startsWith("--") } }
                    .map { it.joinToString("\n").trim().removeSuffix(";") }
                    .filter { it.isNotBlank() }
                    .forEach { sql -> withClue("statement failed:\n$sql") { stmt.execute(sql) } }
            }
        }

        // Und die Objekte stehen wirklich.
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM \"open_orders\"").use { rs ->
                    rs.next() shouldBe true
                }
            }
        }
    }

    test("the unquoted form is what Oracle really rejects") {
        // Die Gegenprobe: ohne das Quoting scheitert genau dieselbe Anweisung.
        val thrown = shouldThrow<SQLException> {
            exec(
                """CREATE TABLE "unquoted_probe" ("id" NUMBER(9), "total_amount" NUMBER(10,2),
                   CONSTRAINT "ck_u" CHECK (total_amount >= 0))""",
            )
        }
        thrown.message!! shouldContain "ORA-00904"
    }
})
