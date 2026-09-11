package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Der **Name** eines einspaltigen UNIQUE-Constraints, gegen echtes PostgreSQL.
 *
 * Bisher faltete der Reverse ihn auf `column.unique` und verwarf den
 * Katalognamen. Ein `DROP CONSTRAINT` bekam dann einen erfundenen
 * (`_unique_email`) — und traf an keiner Datenbank etwas. Was kein Unit-Test
 * zeigen kann: dass der Server den erfundenen Namen wirklich ablehnt und den
 * gelesenen wirklich annimmt.
 */
class PostgresUniqueConstraintNameIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { c ->
            c.createStatement().use { s ->
                s.execute(
                    """CREATE TABLE users (
                         id integer PRIMARY KEY,
                         email text NOT NULL,
                         CONSTRAINT uq_users_email UNIQUE (email))""",
                )
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun exec(sql: String): Result<Unit> = runCatching {
        pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    test("the reverse brings the catalog name back, and a drop uses it") {
        val read = PostgresSchemaReader().read(pool)
        val email = read.schema.tables.getValue("users").columns.getValue("email")

        // Gefaltet wie bisher — aber der Name ist nicht mehr weg.
        email.unique shouldBe true
        email.uniqueConstraintName shouldBe "uq_users_email"

        // Das Soll ohne die Eindeutigkeit: der Vergleich baut den Constraint ab.
        val desired = SchemaDefinition(
            name = read.schema.name,
            version = read.schema.version,
            tables = mapOf(
                "users" to read.schema.tables.getValue("users").let { table ->
                    table.copy(
                        columns = table.columns.mapValues { (name, column) ->
                            if (name == "email") column.copy(unique = false, uniqueConstraintName = null) else column
                        },
                    )
                },
            ),
        )
        val diff = SchemaComparator().compare(read.schema, desired)
        val dropped = diff.tablesChanged.single().constraintsRemoved.single()

        withClue(dropped.toString()) { dropped.name shouldBe "uq_users_email" }

        // Und der Server nimmt genau diesen Namen an — der erfundene scheitert.
        withClue("der erfundene Name darf nicht treffen") {
            exec("""ALTER TABLE "users" DROP CONSTRAINT "_unique_email"""").isFailure shouldBe true
        }
        exec("""ALTER TABLE "users" DROP CONSTRAINT "${dropped.name}"""").isSuccess shouldBe true
    }

    test("a named unique is rendered as a table constraint, so the name survives generate") {
        val schema = SchemaDefinition(
            name = "shop",
            version = "1.0.0",
            tables = mapOf(
                "accounts" to TableDefinition(
                    columns = mapOf(
                        "login" to ColumnDefinition(
                            type = NeutralType.Text(maxLength = 50),
                            required = true,
                            unique = true,
                            uniqueConstraintName = "uq_accounts_login",
                        ),
                    ),
                ),
            ),
        )

        val ddl = PostgresDdlGenerator().generate(schema, DdlGenerationOptions()).render()
        withClue(ddl) {
            ddl shouldContain """CONSTRAINT "uq_accounts_login" UNIQUE ("login")"""
        }

        // Der Server nimmt es an, und der Reverse gibt denselben Namen zurueck.
        exec(ddl).isSuccess shouldBe true
        val readBack = PostgresSchemaReader().read(pool).schema.tables.getValue("accounts")
        readBack.columns.getValue("login").uniqueConstraintName shouldBe "uq_accounts_login"
    }
})
