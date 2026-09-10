package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnGeneration
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
 * Eine berechnete Spalte kommt bei PostgreSQL **mit ihrer Berechnung** zurueck.
 *
 * Frueher tat sie das nicht: das neutrale Modell hatte keine Form dafuer, und
 * eine Meldung (`R343`) war das Einzige, was den Verlust sichtbar machte. Seit
 * es `ColumnGeneration.Computed` gibt, wird der Ausdruck getragen — und die
 * Meldung entfaellt, weil es nichts mehr zu melden gibt.
 *
 * Was kein Unit-Test zeigen kann: ob das Katalog-Praedikat stimmt. Dass
 * `is_generated` `ALWAYS` traegt, was in `generation_expression` steht und in
 * welcher Form der Server ihn zurueckgibt, sagt nur der Server.
 */
class PostgresGeneratedColumnReverseIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:18-alpine")
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

    test("a generated column comes back carrying its expression, and no loss is reported") {
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

        withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
            // Nichts geht mehr verloren, also gibt es auch nichts zu melden.
            read.notes.count { it.code == GeneratedColumnNotes.EXPRESSION_DROPPED } shouldBe 0
        }

        val column = read.schema.tables.getValue("order_line").columns.getValue("line_total")
        val generation = column.generation as? ColumnGeneration.Computed
        withClue(column.generation.toString()) { generation.shouldNotBeNull() }
        // Der Ausdruck kommt in der Normalform des Servers zurueck, nicht als
        // Autorentext — genau deshalb faellt er aus dem Textvergleich heraus.
        generation!!.expression shouldContain "quantity"
        generation.expression shouldContain "unit_price"
        generation.stored shouldBe true

        // Die gewoehnliche Spalte daneben traegt keine Berechnung.
        read.schema.tables.getValue("order_line").columns.getValue("plain_note").generation shouldBe null
    }
})
