package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Materialized Views und ihre Logs sind gewoehnliche Zeilen in `ALL_TABLES`.
 *
 * Eine MV traegt dort ihren eigenen Namen, ein Log den Namen
 * `MLOG${'$'}_<tabelle>`, und `ALL_OBJECTS.SECONDARY` ist bei beiden `N` — der
 * Filter, der die Oracle-Text-Hilfstabellen ausschliesst, greift also nicht.
 * Mitgelesen ergaeben sie Phantom-Tabellen im Reverse und einen Datenpfad,
 * der den Inhalt einer MV kopiert, als waere er eine Tabelle.
 */
class OracleMaterializedViewIntegrationTest : FunSpec({

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

    val setup = listOf(
        """CREATE TABLE "sales" ("id" NUMBER(9) PRIMARY KEY, "amt" NUMBER(10,2), "grp" VARCHAR2(10))""",
        """INSERT INTO "sales" VALUES (1, 10, 'a')""",
        """CREATE MATERIALIZED VIEW LOG ON "sales" WITH PRIMARY KEY, ROWID ("amt") INCLUDING NEW VALUES""",
        """CREATE MATERIALIZED VIEW "mv_totals" REFRESH COMPLETE ON DEMAND
           AS SELECT "grp", SUM("amt") AS "total" FROM "sales" GROUP BY "grp" """,
        """CREATE MATERIALIZED VIEW "mv_rows" REFRESH FAST ON COMMIT
           AS SELECT "id", "amt" FROM "sales" """,
    )

    test("neither a materialized view nor its log is read as a table") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    setup.forEach { sql -> withClue("setup failed:\n$sql") { stmt.execute(sql) } }
                }
                // Der Beleg, dass die Vorbedingung des Tests wirklich gilt:
                // beide MVs und das Log stehen in ALL_TABLES.
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """SELECT COUNT(*) FROM user_tables
                           WHERE table_name IN ('mv_totals', 'mv_rows', 'MLOG${'$'}_sales')""",
                    ).use { rs ->
                        rs.next() shouldBe true
                        withClue("Oracle fuehrt MV/Log nicht mehr in ALL_TABLES — Test ist gegenstandslos") {
                            rs.getInt(1) shouldBe 3
                        }
                    }
                }
            }

            val schema = OracleSchemaReader().read(pool).schema
            withClue("gelesene Tabellen: ${schema.tables.keys}") {
                schema.tables.keys shouldBe setOf("sales")
            }
            // Der Datenpfad haengt an derselben Abfrage.
            withClue("gelistete Tabellen: ${OracleTableLister().listTables(pool)}") {
                OracleTableLister().listTables(pool) shouldBe listOf("sales")
            }
        }
    }
})
