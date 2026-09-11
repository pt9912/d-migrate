package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

    val container = OracleContainer(TestImages.ORACLE)
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

    beforeSpec {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    setup.forEach { sql -> withClue("setup failed:\n$sql") { stmt.execute(sql) } }
                }
            }
        }
    }

    test("neither a materialized view nor its log is read as a table") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
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

    test("materialized views read back with their refresh setting and re-apply") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val schema = OracleSchemaReader().read(pool).schema

            // Beide MVs stehen als Sichten im Modell, nicht als Tabellen.
            withClue("gelesene Sichten: ${schema.views.keys}") {
                schema.views.keys shouldBe setOf("mv_totals", "mv_rows")
            }
            val totals = schema.views.getValue("mv_totals")
            totals.materialized shouldBe true
            totals.refresh shouldBe "complete on demand"
            // ALL_MVIEWS.QUERY ist eine LONG-Spalte; sie kommt unveraendert
            // zurueck, so wie der Autor sie geschrieben hat.
            withClue("Abfrage: ${totals.query}") {
                totals.query!!.contains("SUM(\"amt\")") shouldBe true
            }
            schema.views.getValue("mv_rows").refresh shouldBe "fast on commit"

            // Die FAST-MV laesst sich nicht erzeugen: sie braucht ein Log,
            // das das neutrale Modell nicht traegt. Die andere schon.
            val ddl = OracleDdlGenerator().generate(schema)
            withClue("uebergangen: ${ddl.skippedObjects.map { it.name to it.code }}") {
                ddl.skippedObjects.single { it.name == "mv_rows" }.code shouldBe "E053"
            }
            val create = ddl.statements.map { it.sql }.single { it.startsWith("CREATE MATERIALIZED VIEW") }
            create shouldContain "REFRESH COMPLETE ON DEMAND"

            // Und sie laesst sich wirklich anwenden.
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("""DROP MATERIALIZED VIEW "mv_totals" """)
                    withClue("statement failed:\n$create") { stmt.execute(create.removeSuffix(";")) }
                }
                conn.createStatement().use { stmt ->
                    stmt.executeQuery(
                        """SELECT refresh_method || ' ' || refresh_mode FROM user_mviews
                           WHERE mview_name = 'mv_totals'""",
                    ).use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "COMPLETE DEMAND"
                    }
                }
            }
        }
    }
})
