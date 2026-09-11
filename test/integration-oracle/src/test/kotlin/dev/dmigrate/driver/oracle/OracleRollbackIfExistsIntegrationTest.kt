package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlDialectContext
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.OracleServerVersion
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Ein Ruecknahme-Skript fuehrt man, wenn etwas schiefging — und dann fehlt
 * regelmaessig schon ein Teil der Objekte. Ohne `IF EXISTS` bricht es an der
 * ersten solchen Stelle ab und laesst den Rest stehen.
 *
 * Gemessen wird deshalb beides gegen ein echtes Oracle: dass der Lesepfad die
 * Version ueberhaupt beibringt, und dass das damit erzeugte Skript ein
 * zweites Mal durchlaeuft.
 */
class OracleRollbackIfExistsIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("""CREATE TABLE "rb_t" ("id" NUMBER(10) NOT NULL)""")
                stmt.execute("""CREATE INDEX "rb_ix" ON "rb_t" ("id")""")
                stmt.execute("""CREATE SEQUENCE "rb_seq"""")
                stmt.execute("""CREATE OR REPLACE FORCE VIEW "rb_v" AS SELECT "id" FROM "rb_t"""")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("der Lesepfad bringt die Version mit, an der die Klausel haengt") {
        val version = OracleSchemaReader().read(pool).serverVersion

        withClue("gelesene Version: $version") {
            version.shouldNotBeNull()
            (version as OracleServerVersion).supportsDropIfExists shouldBe true
        }
    }

    test("das erzeugte Ruecknahme-Skript laeuft ein zweites Mal durch") {
        val read = OracleSchemaReader().read(pool)
        val options = DdlGenerationOptions(
            dialectContext = DdlDialectContext.Oracle(
                serverVersion = read.serverVersion as? OracleServerVersion,
            ),
        )
        val rollback = OracleDdlGenerator().generateRollback(read.schema, options)
        val statements = rollback.statements
            .map { it.sql.trim().removeSuffix(";") }
            .filter { it.isNotBlank() && !it.startsWith("--") }

        withClue("gerendert: $statements") {
            statements.any { it.contains("IF EXISTS") } shouldBe true
        }

        // Zweimal: der erste Lauf raeumt weg, der zweite trifft auf nichts
        // mehr. Genau der Fall, an dem die Form ohne Klausel scheiterte.
        repeat(2) { round ->
            pool.borrow().asJdbc().use { conn ->
                for (sql in statements) {
                    val failure = runCatching {
                        conn.createStatement().use { it.execute(sql) }
                    }.exceptionOrNull()
                    withClue("Durchlauf $round: $sql -> ${failure?.message}") {
                        failure shouldBe null
                    }
                }
            }
        }
    }
})
