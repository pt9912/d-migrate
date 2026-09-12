package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.GeneratedColumnNotes
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.time.Duration

/**
 * Beide Formen der berechneten Spalte kommen bei Oracle als berechnete zurueck
 * — und die gewoehnliche Spalte mit `DEFAULT` bleibt eine gewoehnliche.
 *
 * Was kein Unit-Test zeigen kann, ist genau der Grund, warum der Lesepfad hier
 * zwei Quellen braucht: die **virtuelle** Spalte steht mit
 * `VIRTUAL_COLUMN = 'YES'` im Katalog, die **materialisierte** nicht — sie
 * steht dort wie eine gewoehnliche Spalte mit `DEFAULT`, in jedem Feld von
 * `ALL_TAB_COLS` (gemessen, nicht der Doku entnommen). Der Ausdruck kommt bei
 * beiden aus `DATA_DEFAULT`; die Einordnung der materialisierten kommt aus
 * `DBMS_METADATA.GET_DDL`.
 */
class OracleGeneratedColumnReverseIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE "order_line" (
                         "id" NUMBER(9) NOT NULL PRIMARY KEY,
                         "quantity" NUMBER(9) NOT NULL,
                         "unit_price" NUMBER(12,2) NOT NULL,
                         "line_total" NUMBER(14,2) GENERATED ALWAYS AS ("quantity" * "unit_price") VIRTUAL,
                         "line_net" NUMBER(14,2) GENERATED ALWAYS AS ("quantity" * "unit_price" * 2) MATERIALIZED,
                         "discount" NUMBER(14,2) DEFAULT 7,
                         "plain_note" VARCHAR2(40))""",
                )
            }
        }
    }

    afterSpec {
        runCatching {
            pool.borrow().asJdbc().use { c ->
                c.createStatement().use { it.execute("""DROP TABLE "order_line" PURGE""") }
            }
        }
        runCatching { pool.close() }
        container.stop()
    }

    test("a virtual column comes back virtual, with its expression") {
        val table = OracleSchemaReader().read(pool).schema.tables.getValue("order_line")
        val column = table.columns.getValue("line_total")
        val computed = column.generation as? ColumnGeneration.Computed

        withClue(column.generation.toString()) {
            computed.shouldNotBeNull()
            computed.stored shouldBe false
            // Serverform: Oracle gibt den Ausdruck quotiert und ohne Leerraum
            // zurueck, nicht den Autorentext.
            computed.expression shouldBe """"quantity"*"unit_price""""
        }
        // Der Wert kommt aus dem Ausdruck — ein Default daneben waere derselbe
        // Text an zweiter Stelle.
        column.default.shouldBeNull()
    }

    test("a materialized column comes back stored, with its expression") {
        val table = OracleSchemaReader().read(pool).schema.tables.getValue("order_line")
        val column = table.columns.getValue("line_net")
        val computed = column.generation as? ColumnGeneration.Computed

        withClue(column.generation.toString()) {
            computed.shouldNotBeNull()
            computed.stored shouldBe true
            computed.expression shouldBe """"quantity"*"unit_price"*2"""
        }
        column.default.shouldBeNull()
    }

    test("a plain default stays a default, and a plain column stays plain") {
        val table = OracleSchemaReader().read(pool).schema.tables.getValue("order_line")

        table.columns.getValue("discount").generation.shouldBeNull()
        table.columns.getValue("discount").default.shouldNotBeNull()
        table.columns.getValue("plain_note").generation.shouldBeNull()
        table.columns.getValue("quantity").generation.shouldBeNull()
    }

    test("nothing is reported as lost") {
        val read = OracleSchemaReader().read(pool)
        val codes = setOf(
            GeneratedColumnNotes.EXPRESSION_DROPPED,
            GeneratedColumnNotes.GENERATION_UNDECIDABLE,
        )
        withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
            read.notes.filter { it.code in codes } shouldBe emptyList()
        }
    }
})
