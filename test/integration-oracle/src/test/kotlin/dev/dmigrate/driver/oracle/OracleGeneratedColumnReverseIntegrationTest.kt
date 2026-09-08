package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Eine virtuelle Spalte kommt bei Oracle als gewoehnliche zurueck — die
 * Berechnung ist weg, und die Meldung ist das Einzige, was den Verlust
 * sichtbar macht, solange das neutrale Modell keine Form dafuer hat.
 *
 * Der Lesepfad benutzt dafuer dieselbe Abfrage wie der Schreibpfad, der
 * virtuelle Spalten schon immer aussparen musste. Was kein Unit-Test zeigen
 * kann: dass `all_tab_cols.virtual_column` fuer eine sichtbare virtuelle
 * Spalte wirklich `YES` traegt und der Ausdruck in `data_default` steht.
 */
class OracleGeneratedColumnReverseIntegrationTest : FunSpec({

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
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    test("a virtual column is reported, with its expression, and reads as a plain column") {
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE "order_line" (
                         "id" NUMBER(9) NOT NULL PRIMARY KEY,
                         "quantity" NUMBER(9) NOT NULL,
                         "unit_price" NUMBER(12,2) NOT NULL,
                         "line_total" NUMBER(14,2) GENERATED ALWAYS AS ("quantity" * "unit_price") VIRTUAL,
                         "plain_note" VARCHAR2(40))""",
                )
            }
        }
        try {
            val read = OracleSchemaReader().read(pool)
            val notes = read.notes.filter { it.code == GeneratedColumnNotes.EXPRESSION_DROPPED }

            withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
                notes.map { it.objectName } shouldBe listOf("order_line.line_total")
            }
            notes.single().message shouldContain "quantity"

            // Der gemessene Ist-Zustand: die Spalte ist da, ihre Berechnung nicht.
            read.schema.tables.getValue("order_line").columns
                .getValue("line_total").generation shouldBe null
        } finally {
            runCatching {
                pool.borrow().asJdbc().use { c ->
                    c.createStatement().use { it.execute("""DROP TABLE "order_line" PURGE""") }
                }
            }
        }
    }
})
