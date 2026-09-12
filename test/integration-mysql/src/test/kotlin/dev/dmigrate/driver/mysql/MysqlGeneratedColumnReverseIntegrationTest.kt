package dev.dmigrate.driver.mysql

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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.MySQLContainer

/**
 * Eine berechnete Spalte kommt bei MySQL als berechnete zurueck — mit ihrem
 * Ausdruck und ihrer Speicherform.
 *
 * Was kein Unit-Test zeigen kann: die Schreibweise, an der der Leser die
 * Spalte erkennt. MySQL fuehrt sie in `information_schema.columns.extra` als
 * `STORED GENERATED` bzw. `VIRTUAL GENERATED`, den Ausdruck daneben in
 * `generation_expression` — und zwar in der Serverform mit Backticks
 * (`(`quantity` * `unit_price`)`), nicht im Autorentext. Dass beide Formen das
 * Wort `GENERATED` tragen und wie der Ausdruck zurueckkommt, sagt nur der
 * Server.
 *
 * Zuvor meldete der Leser hier einen Verlust (`EXPRESSION_DROPPED`), weil das
 * neutrale Modell keine Form dafuer hatte. Die gibt es jetzt.
 */
class MysqlGeneratedColumnReverseIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
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

    test("both stored and virtual generated columns come back as computed") {
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

        // Kein Verlust mehr zu melden: die Berechnung wird getragen.
        withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
            read.notes.none { it.code == GeneratedColumnNotes.EXPRESSION_DROPPED } shouldBe true
        }

        val columns = read.schema.tables.getValue("order_line").columns
        val stored = columns.getValue("line_total").generation as? ColumnGeneration.Computed
        val virtual = columns.getValue("line_note").generation as? ColumnGeneration.Computed

        withClue(columns.getValue("line_total").generation.toString()) {
            stored.shouldNotBeNull()
            stored.stored shouldBe true
            // Serverform, nicht Autorentext — genau deshalb faellt der Ausdruck
            // aus dem Textvergleich heraus.
            stored.expression shouldContain "quantity"
        }
        withClue(columns.getValue("line_note").generation.toString()) {
            virtual.shouldNotBeNull()
            virtual.stored shouldBe false
        }
        // Eine gewoehnliche Spalte bleibt eine gewoehnliche.
        columns.getValue("plain_note").generation shouldBe null
    }

    /**
     * Die Abgrenzung, die der Leser vorher nicht machte: MySQL setzt `EXTRA`
     * auch auf `DEFAULT_GENERATED` -- fuer eine Spalte mit Default-**Ausdruck**
     * (ab 8.0.13). Wer nur auf das Wort `GENERATED` hoert, meldet fuer jedes
     * `DEFAULT CURRENT_TIMESTAMP` einen Verlust, den es nicht gibt.
     */
    test("a column with a default expression is no computed column, and reports no loss") {
        val active = pool!!
        active.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE audit_entry (
                         id INT PRIMARY KEY,
                         created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         touched_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP
                           ON UPDATE CURRENT_TIMESTAMP)""",
                )
            }
        }

        // Gemessen, nicht angenommen: so nennt der Server diese beiden Spalten.
        val extras = mutableMapOf<String, String>()
        active.borrow().asJdbc().use { c ->
            c.prepareStatement(
                "SELECT column_name, extra FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = 'audit_entry'",
            ).use { ps ->
                ps.executeQuery().use { rs -> while (rs.next()) extras[rs.getString(1)] = rs.getString(2) }
            }
        }
        extras["created_at"] shouldBe "DEFAULT_GENERATED"
        extras["touched_at"] shouldBe "DEFAULT_GENERATED on update CURRENT_TIMESTAMP"

        val read = MysqlSchemaReader().read(active)
        val columns = read.schema.tables.getValue("audit_entry").columns
        columns.getValue("created_at").generation shouldBe null
        columns.getValue("touched_at").generation shouldBe null
        withClue(read.notes.map { "${it.code}:${it.objectName}" }.toString()) {
            read.notes.none {
                it.code == GeneratedColumnNotes.EXPRESSION_DROPPED && it.objectName.contains("audit_entry")
            } shouldBe true
        }
    }
})
