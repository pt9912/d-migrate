package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.MySQLContainer

/**
 * Eine berechnete Spalte kommt bei MySQL als gewoehnliche zurueck — die
 * Berechnung ist weg, und die Meldung ist das Einzige, was den Verlust
 * sichtbar macht, solange das neutrale Modell keine Form dafuer hat.
 *
 * Was kein Unit-Test zeigen kann: die Schreibweise, an der der Leser die
 * Spalte erkennt. MySQL fuehrt sie in `information_schema.columns.extra` als
 * `STORED GENERATED` bzw. `VIRTUAL GENERATED` — dass beide Formen das Wort
 * tragen und wie der Ausdruck in `generation_expression` aussieht, sagt nur
 * der Server.
 */
class MysqlGeneratedColumnReverseIntegrationTest : FunSpec({

    val container = MySQLContainer("mysql:9.7.2")
        .withDatabaseName("dmigrate_generated")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    var pool: ConnectionPool? = null

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.MYSQL,
                host = container.host,
                port = container.firstMappedPort,
                database = container.databaseName,
                user = container.username,
                password = container.password,
                params = mapOf("allowPublicKeyRetrieval" to "true"),
            ),
        )
    }

    afterSpec {
        pool?.close()
        container.stop()
    }

    test("both stored and virtual generated columns are reported, with their expression") {
        val active = pool!!
        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE order_line (
                         id INT PRIMARY KEY,
                         quantity INT NOT NULL,
                         unit_price DECIMAL(12,2) NOT NULL,
                         line_total DECIMAL(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
                         line_note VARCHAR(40) GENERATED ALWAYS AS (CONCAT('n', id)) VIRTUAL,
                         plain_note VARCHAR(40))""",
                )
            }
        }

        val read = MysqlSchemaReader().read(active)
        val notes = read.notes.filter { it.code == GeneratedColumnNotes.EXPRESSION_DROPPED }

        withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
            notes.map { it.objectName }.sorted() shouldContainExactly
                listOf("order_line.line_note", "order_line.line_total")
        }
        // Der Ausdruck steht in der Meldung — ohne ihn wuesste der Anwender
        // nicht, was er auf dem Ziel nachbauen soll.
        notes.single { it.objectName == "order_line.line_total" }.message shouldContain "quantity"

        // Der gemessene Ist-Zustand: die Spalten sind da, ihre Berechnung nicht.
        val columns = read.schema.tables.getValue("order_line").columns
        columns.getValue("line_total").generation shouldBe null
        columns.containsKey("line_note") shouldBe true
    }
})
