package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Slice 6a: der Index-Rueckweg gegen ein ECHTES Oracle.
 *
 * Zwei Gruende, warum Mocks hier nicht reichen:
 *
 * 1. `ALL_IND_EXPRESSIONS.COLUMN_EXPRESSION` ist eine Oracle-**LONG**-Spalte.
 *    `JdbcMetadataSession` liest jede Spalte ueber `rs.getObject(col)`, und
 *    LONG hat mit Row-Prefetch eine bekannte Eigenheit
 *    (`oracle.jdbc.useFetchSizeWithLongColumn`). Kaeme dort leer statt
 *    `"AMT"` zurueck, faende die Rueckfaltung nichts -- und JEDER
 *    DESC-Index verschwaende still mit R354, also genau der Verlust, den
 *    Slice 6a beseitigt. Ein Mock kann das nicht sehen: er liefert den Wert
 *    definitionsgemaess.
 * 2. Die Katalogwerte in `INDEX_TYPE` (`FUNCTION-BASED BITMAP` &c.) sind
 *    gemessene Zeichenketten, keine dokumentierte Zusage.
 */
class OracleIndexReverseIntegrationTest : FunSpec({

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

    test("index kinds come back the way the catalog really reports them") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("BEGIN EXECUTE IMMEDIATE 'DROP TABLE \"facts\"'; EXCEPTION WHEN OTHERS THEN NULL; END;")
                    stmt.execute(
                        """CREATE TABLE "facts" ("id" NUMBER(9) PRIMARY KEY, "status" VARCHAR2(10),
                           "amt" NUMBER(9), "nm" VARCHAR2(50))""".trimIndent(),
                    )
                    stmt.execute("""CREATE BITMAP INDEX "bm_status" ON "facts" ("status")""")
                    stmt.execute("""CREATE BITMAP INDEX "bm_multi" ON "facts" ("status", "amt")""")
                    stmt.execute("""CREATE INDEX "ix_plain" ON "facts" ("amt")""")
                    stmt.execute("""CREATE INDEX "ix_desc" ON "facts" ("amt" DESC)""")
                    stmt.execute("""CREATE INDEX "ix_fn" ON "facts" (UPPER("nm"))""")
                }
            }

            val result = OracleSchemaReader().read(pool)
            val indices = result.schema.tables.getValue("facts").indices.associateBy { it.name }

            withClue("gelesene Indizes: ${indices.keys}") {
                indices.keys shouldBe setOf("bm_status", "bm_multi", "ix_plain", "ix_desc")
            }
            indices.getValue("bm_status").type shouldBe IndexType.BITMAP
            indices.getValue("bm_multi").type shouldBe IndexType.BITMAP
            indices.getValue("bm_multi").columnNames shouldBe listOf("status", "amt")
            indices.getValue("ix_plain").type shouldBe IndexType.BTREE

            // Der Kern: Oracle setzt "amt" DESC intern als Ausdruck um und
            // fuehrt an der Stelle die unsichtbare Spalte SYS_NC0000n$. Kommt
            // hier deren Name oder gar nichts an, ist die LONG-Spalte nicht
            // gelesen worden.
            val desc = indices.getValue("ix_desc")
            desc.type shouldBe IndexType.BTREE
            desc.columnNames shouldBe listOf("amt")
            desc.columns.single().direction shouldBe IndexSortDirection.DESC

            // Der echte Ausdrucks-Index ist ausgelassen, aber nicht stumm.
            val note = result.notes.single { it.code == "R354" }
            note.objectName shouldBe "ix_fn"
            note.severity shouldBe SchemaReadSeverity.WARNING
        }
    }
})
