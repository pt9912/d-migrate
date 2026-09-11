package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Oracle nimmt ein `CREATE OR REPLACE` mit einem Fehler im Rumpf ueber JDBC an
 * und meldet Erfolg — das Objekt steht danach unbenutzbar im Katalog. Ohne
 * diese Nachfrage gilt der Lauf als gelungen.
 *
 * Gemessen wird gegen ein echtes Oracle, weil die Unterscheidung, auf der die
 * Sonde beruht, eine Serverzusage ist und keine Modellannahme: `INVALID` allein
 * ist mehrdeutig, ein Eintrag in `ALL_ERRORS` ist es nicht.
 */
class OraclePostApplyStatusIntegrationTest : FunSpec({

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

    fun exec(sql: String) = pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute(sql) } }

    fun planFor(vararg names: String) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = names.map { name ->
            DiffOperation.CreateFunction(
                id = "create:$name",
                objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf(name)),
                function = FunctionDefinition(returns = ReturnType(type = "integer"), body = "BEGIN RETURN 1; END;"),
            )
        },
    )

    test("ein Rumpf, den Oracle nicht uebersetzen kann, wird gefunden — obwohl das DDL gelang") {
        exec(
            """CREATE OR REPLACE FUNCTION "pa_broken" RETURN NUMBER IS BEGIN RETURN kein_bezeichner; END;""",
        )

        val found = pool.borrow().use { conn ->
            OraclePostApplyStatusProbe.probe(conn, planFor("pa_broken"))
        }

        withClue("gefunden: $found") {
            found.isNotEmpty() shouldBe true
            found.first().name shouldBe "pa_broken"
            found.first().objectType shouldBe "FUNCTION"
            found.first().message shouldContain "PLS-00201"
        }
    }

    test("eine uebersetzbare Routine erzeugt keinen Befund") {
        exec("""CREATE TABLE "pa_t" ("a" NUMBER)""")
        exec(
            """CREATE OR REPLACE FUNCTION "pa_good" RETURN NUMBER IS n NUMBER; """ +
                """BEGIN SELECT "a" INTO n FROM "pa_t" WHERE ROWNUM = 1; RETURN n; END;""",
        )

        pool.borrow().use { conn ->
            OraclePostApplyStatusProbe.probe(conn, planFor("pa_good"))
        }.shouldBeEmpty()
    }

    test("planmaessige Invalidierung ist kein Befund — sonst meldete jede Tabellenaenderung Fehler") {
        // Faellt eine benutzte Spalte weg, stellt Oracle die Abhaengigen auf
        // INVALID und uebersetzt sie bei der naechsten Benutzung selbst neu.
        // ALL_ERRORS bleibt dabei leer; genau daran haengt die Unterscheidung.
        exec("""CREATE TABLE "pa_u" ("a" NUMBER, "b" NUMBER)""")
        exec(
            """CREATE OR REPLACE FUNCTION "pa_dep" RETURN NUMBER IS n NUMBER; """ +
                """BEGIN SELECT "b" INTO n FROM "pa_u" WHERE ROWNUM = 1; RETURN n; END;""",
        )
        exec("""ALTER TABLE "pa_u" DROP COLUMN "b"""")

        val status = pool.borrow().asJdbc().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    """SELECT status FROM all_objects WHERE object_name = 'pa_dep' """ +
                        """AND owner = SYS_CONTEXT('USERENV','CURRENT_SCHEMA')""",
                ).use { rs -> rs.next(); rs.getString(1) }
            }
        }

        withClue("Der Test belegt nur etwas, wenn Oracle wirklich invalidiert hat") {
            status shouldBe "INVALID"
        }
        pool.borrow().use { conn ->
            OraclePostApplyStatusProbe.probe(conn, planFor("pa_dep"))
        }.shouldBeEmpty()
    }

    test("ein kaputtes Objekt, das dieser Lauf nicht angefasst hat, bleibt aussen vor") {
        exec(
            """CREATE OR REPLACE FUNCTION "pa_alt_kaputt" RETURN NUMBER IS BEGIN RETURN nix; END;""",
        )

        pool.borrow().use { conn ->
            OraclePostApplyStatusProbe.probe(conn, planFor("pa_good"))
        }.shouldBeEmpty()
    }
})
