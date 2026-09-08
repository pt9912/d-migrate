package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Eine berechnete Spalte kommt bei PostgreSQL als gewoehnliche zurueck — die
 * Berechnung ist weg, und bis das neutrale Modell eine Form dafuer hat, ist
 * die Meldung das Einzige, was den Verlust sichtbar macht.
 *
 * Was kein Unit-Test zeigen kann: ob das Katalog-Praedikat stimmt.
 * `information_schema.columns.is_generated` traegt `ALWAYS` bzw. `NEVER` — ob
 * PostgreSQL das fuer eine `STORED`-Spalte wirklich so meldet und was in
 * `generation_expression` steht, sagt nur der Server.
 */
class PostgresGeneratedColumnReverseIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host,
                port = container.firstMappedPort,
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

    test("a generated column is reported, with its expression, and reads as a plain column") {
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE order_line (
                         id integer PRIMARY KEY,
                         quantity integer NOT NULL,
                         unit_price numeric(12,2) NOT NULL,
                         line_total numeric(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
                         plain_note text)""",
                )
            }
        }

        val read = PostgresSchemaReader().read(pool)
        val note = read.notes.singleOrNull { it.code == GeneratedColumnNotes.EXPRESSION_DROPPED }

        withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
            note.shouldNotBeNull()
        }
        note!!.objectName shouldBe "order_line.line_total"
        // Der Ausdruck steht in der Meldung — ohne ihn wuesste der Anwender
        // nicht, was er auf dem Ziel nachbauen soll.
        note.message shouldContain "quantity"
        note.message shouldContain "unit_price"

        // Der gemessene Ist-Zustand: die Spalte ist da, ihre Berechnung nicht.
        val column = read.schema.tables.getValue("order_line").columns.getValue("line_total")
        column.generation shouldBe null

        // Die gewoehnliche Spalte daneben loest keine Meldung aus.
        read.notes.count { it.code == GeneratedColumnNotes.EXPRESSION_DROPPED } shouldBe 1
    }
})
