package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Welche der beiden Formen einer berechneten Spalte vorliegt, sagt nur
 * `pg_attribute.attgenerated`.
 *
 * `information_schema.is_generated` meldet fuer die gespeicherte **und** die
 * virtuelle Form `ALWAYS` — gegen 18.6 gemessen. Wer daran haengt, macht aus
 * einer virtuellen Spalte still eine gespeicherte: aus einem Ausdruck, der bei
 * jedem Lesen neu rechnet, wird einer, dessen Ergebnis auf der Platte liegt.
 *
 * Die Spec laeuft gegen **18**, weil es die virtuelle Form erst dort gibt. Bis
 * 17 ist `STORED` Pflicht, und dann ist die Frage nicht stellbar.
 */
class PostgresGeneratedKindReverseIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE order_line (" +
                        "quantity int NOT NULL, unit_price numeric(12,2) NOT NULL, " +
                        "total_stored numeric(14,2) GENERATED ALWAYS AS (quantity * unit_price) STORED, " +
                        "total_virtual numeric(14,2) GENERATED ALWAYS AS (quantity * unit_price) VIRTUAL, " +
                        "total_default numeric(14,2) GENERATED ALWAYS AS (quantity * unit_price))",
                )
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun generationOf(column: String): ColumnGeneration.Computed? =
        PostgresSchemaReader().read(pool).schema.tables.getValue("order_line")
            .columns.getValue(column).generation as? ColumnGeneration.Computed

    test("the stored form reads back as stored") {
        val generation = generationOf("total_stored")

        generation.shouldNotBeNull()
        generation.stored shouldBe true
    }

    test("the virtual form reads back as virtual, not as stored") {
        val generation = generationOf("total_virtual")

        generation.shouldNotBeNull()
        withClue("eine virtuelle Spalte als gespeichert zu lesen legt ihr Ergebnis auf die Platte") {
            generation.stored shouldBe false
        }
    }

    test("without a keyword PostgreSQL 18 chooses the virtual form") {
        // Die Vorgabe hat sich gedreht: bis 17 war `STORED` Pflicht.
        val generation = generationOf("total_default")

        generation.shouldNotBeNull()
        generation.stored shouldBe false
    }

    test("information_schema alone cannot tell them apart") {
        // Die Gegenprobe zur Zusicherung oben: waere `is_generated` die Quelle,
        // saehen alle drei Spalten gleich aus.
        val kinds = pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.executeQuery(
                    "SELECT string_agg(DISTINCT is_generated, ',') FROM information_schema.columns " +
                        "WHERE table_name = 'order_line' AND column_name LIKE 'total\\_%'",
                ).use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

        kinds shouldBe "ALWAYS"
    }
})
