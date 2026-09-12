package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Ein Primaerschluessel erzeugt keinen Unterschied mehr, den niemand
 * herstellen kann.
 *
 * Der Release-Smoke aus `releasing.md` 3.3 legte eine Datenbank aus
 * `minimal.yaml` an und verglich die Fixture dagegen — und bekam
 * `required: false -> true` an der PK-Spalte. Zwei Ursachen, beide hier
 * belegt:
 *
 * 1. PostgreSQL war der **einzige** Leser, der die Reverse-Konvention
 *    „PK impliziert required und unique, also behaupte keines von beiden"
 *    nur zur Haelfte hielt: `unique` faltete er, `required` nicht.
 * 2. Der strikte Vergleich meldete den Unterschied auch dann, wenn eine
 *    Seite ihn ausschrieb — auf keinem Dialekt laesst sich eine PK-Spalte
 *    nullable machen, der Unterschied war also nirgends aufloesbar.
 *
 * Was kein Unit-Test zeigen kann: dass `information_schema` fuer eine
 * PK-Spalte wirklich `is_nullable = 'NO'` meldet — der Server materialisiert
 * das `NOT NULL`, ohne dass es jemand hingeschrieben hat.
 */
class PostgresPrimaryKeyRequiredIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
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
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use {
                // Wortgleich das, was `schema generate --target postgresql`
                // aus `minimal.yaml` erzeugt.
                it.execute(
                    """CREATE TABLE "users" (
                         "id" SERIAL,
                         "name" VARCHAR(100) NOT NULL,
                         PRIMARY KEY ("id")
                       )""",
                )
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    /** Die Fixture: `required` steht an der PK-Spalte NICHT. */
    val fixture = SchemaDefinition(
        name = "Minimal Schema", version = "1.0.0",
        tables = mapOf(
            "users" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Identifier(autoIncrement = true)),
                    "name" to ColumnDefinition(NeutralType.Text(maxLength = 100), required = true),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun read() = PostgresSchemaReader().read(
        pool,
        SchemaReadOptions(
            includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false,
        ),
    ).schema

    test("the server really materialises NOT NULL on the primary key column") {
        val nullable = pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(
                    """SELECT is_nullable FROM information_schema.columns
                       WHERE table_name = 'users' AND column_name = 'id'""",
                ).use { rs -> rs.next(); rs.getString(1) }
            }
        }
        withClue("ohne diese Zusicherung gaebe es den Befund gar nicht") { nullable shouldBe "NO" }
    }

    test("the reverse follows the same convention as the other four dialects") {
        val id = read().tables.getValue("users").columns.getValue("id")
        withClue(id.toString()) {
            id.required shouldBe false
            id.unique shouldBe false
        }
        // Eine gewoehnliche NOT-NULL-Spalte bleibt davon unberuehrt.
        read().tables.getValue("users").columns.getValue("name").required shouldBe true
    }

    // Verglichen werden die TABELLEN. Name und Version unterscheiden sich
    // zwangslaeufig — der Reverse synthetisiert sie aus der Verbindung
    // (`__dmigrate_reverse__:postgresql:…`), die Fixture traegt ihre eigenen.
    test("a strict compare of the fixture against the database it produced finds no table difference") {
        val diff = SchemaComparator().compare(read(), fixture)
        withClue(diff.toString()) {
            diff.tablesChanged shouldBe emptyList()
            diff.tablesAdded shouldBe emptyList()
            diff.tablesRemoved shouldBe emptyList()
        }
    }

    test("spelling required out at the primary key column changes nothing") {
        val spelledOut = fixture.copy(
            tables = mapOf(
                "users" to fixture.tables.getValue("users").let { t ->
                    t.copy(
                        columns = linkedMapOf(
                            "id" to t.columns.getValue("id").copy(required = true),
                            "name" to t.columns.getValue("name"),
                        ),
                    )
                },
            ),
        )
        val diff = SchemaComparator().compare(read(), spelledOut)
        withClue(diff.toString()) { diff.tablesChanged shouldBe emptyList() }
    }
})
