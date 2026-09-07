package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Sequenzgrenzen gegen ein ECHTES Oracle.
 *
 * `NOMINVALUE`/`NOMAXVALUE` materialisieren in `ALL_SEQUENCES` als konkrete
 * Zahlen, und die obere liegt ausserhalb des `Long`-Bereichs. Was der Server
 * dort wirklich ablegt, ist eine gemessene Zahl und keine dokumentierte
 * Zusage — ein Mock liefert definitionsgemaess, was man ihm vorgibt, und
 * genau daran ging der Fehler vorbei: die Unit-Tests stubten
 * `Long.MAX_VALUE`, einen Wert, den Oracle an dieser Stelle nie liefert.
 *
 * Ohne die Behandlung verkuerzte `BigDecimal.toLong()` still auf die unteren
 * 64 Bit — aus einer unbegrenzten Sequenz wurde eine bei rund 4,5 Trillionen
 * begrenzte, ohne Fehler und ohne Notiz.
 */
class OracleSequenceReverseIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
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
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                for (name in listOf("s_up", "s_down", "s_bounded")) {
                    stmt.execute(
                        "BEGIN EXECUTE IMMEDIATE 'DROP SEQUENCE \"$name\"'; " +
                            "EXCEPTION WHEN OTHERS THEN NULL; END;",
                    )
                }
                stmt.execute("""CREATE SEQUENCE "s_up"""")
                stmt.execute("""CREATE SEQUENCE "s_down" INCREMENT BY -1 START WITH -1""")
                stmt.execute("""CREATE SEQUENCE "s_bounded" MINVALUE 5 MAXVALUE 100 START WITH 5""")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("a sequence without declared bounds comes back without them") {
        val sequences = OracleSchemaReader().read(pool).schema.sequences

        withClue("gelesene Sequenzen: ${sequences.keys}") {
            sequences.containsKey("s_up") shouldBe true
        }

        // Aufsteigend: Oracle traegt `min = 1` und `max = 10^28 - 1`.
        sequences.getValue("s_up").minValue shouldBe null
        sequences.getValue("s_up").maxValue shouldBe null

        // Absteigend: `min = -(10^27 - 1)` und `max = -1`.
        sequences.getValue("s_down").minValue shouldBe null
        sequences.getValue("s_down").maxValue shouldBe null

        // Erklaerte Grenzen bleiben stehen -- gefaltet wird nur der Default.
        sequences.getValue("s_bounded").minValue shouldBe 5L
        sequences.getValue("s_bounded").maxValue shouldBe 100L
    }

    test("the catalog really carries the out-of-range defaults this rests on") {
        // Faellt diese Zusicherung, ist die Behandlung oben auf einen Wert
        // gebaut, den der Server nicht mehr liefert.
        val bounds = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """SELECT TO_CHAR(min_value), TO_CHAR(max_value) FROM user_sequences
                       WHERE sequence_name = 's_up'""",
                ).use { rs -> if (rs.next()) rs.getString(1) to rs.getString(2) else null }
            }
        }

        bounds shouldBe ("1" to "9999999999999999999999999999")
    }
})
