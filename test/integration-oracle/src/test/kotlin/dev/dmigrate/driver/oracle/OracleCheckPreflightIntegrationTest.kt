package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Sub-Slice 5e-2: die Oracle-CHECK-Preflight-Sonde gegen ein ECHTES Oracle.
 *
 * Der Wert der Sonde haengt daran, dass ihre Abfrage auf Oracle laeuft und
 * richtig zaehlt — das kann ein Unit-Test mit gestubbter Verbindung nicht
 * belegen. Fuer Oracle ist sie kein Komfort: Oracle validiert einen
 * hinzugefuegten CHECK per Default gegen den Bestand (`ORA-02293`), und der
 * Diff-Pfad rendert bewusst kein `ENABLE NOVALIDATE`. Ohne Sonde erfuehre
 * der Anwender den Konflikt erst, wenn das Apply mitten im Lauf abbricht.
 */
class OracleCheckPreflightIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        config = ConnectionConfig(
            dialect = DatabaseDialect.ORACLE,
            host = container.host,
            port = container.oraclePort,
            database = container.databaseName,
            user = container.username,
            password = container.password,
        )
    }

    afterSpec { container.stop() }

    fun addCheck(table: String, name: String, expression: String): DiffResult {
        val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table, name))
        val endpoint = DiffEndpoint(schemaName = "app", schemaVersion = "1", fingerprint = "fp")
        return DiffResult(
            current = endpoint,
            desired = endpoint,
            schemaDiff = SchemaDiff(),
            operations = listOf(
                DiffOperation.AddConstraint(
                    id = "AddConstraint:$name",
                    objectRef = ref,
                    constraint = ConstraintDefinition(
                        name = name,
                        type = ConstraintType.CHECK,
                        expression = expression,
                    ),
                ),
            ),
        )
    }

    test("a CHECK that holds against the existing rows passes, one that does not reports the row count") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""CREATE TABLE "orders" ("id" NUMBER(10), "amount" NUMBER(10))""")
                    stmt.execute("""INSERT INTO "orders" VALUES (1, 10)""")
                    stmt.execute("""INSERT INTO "orders" VALUES (2, -5)""")
                    stmt.execute("""INSERT INTO "orders" VALUES (3, -7)""")
                }

                val passing = OracleCheckPreflightProbe.probe(
                    conn,
                    addCheck("orders", "ck_orders_id", """"id" > 0"""),
                ).single()
                passing.status shouldBe CheckPreflightStatus.PASSED
                passing.failingRows shouldBe null

                val failing = OracleCheckPreflightProbe.probe(
                    conn,
                    addCheck("orders", "ck_orders_amount", """"amount" >= 0"""),
                ).single()
                failing.status shouldBe CheckPreflightStatus.FAILED
                // Genau die beiden negativen Zeilen -- nicht "irgendwas > 0".
                failing.failingRows shouldBe 2L

                // Eine Abfrage, die Oracle gar nicht ausfuehren kann, wird
                // als Sondenfehler gemeldet und nicht als bestanden.
                val broken = OracleCheckPreflightProbe.probe(
                    conn,
                    addCheck("orders", "ck_orders_broken", """"no_such_column" > 0"""),
                ).single()
                broken.status shouldBe CheckPreflightStatus.PROBE_RUNTIME_ERROR
            }
        }
    }
})
