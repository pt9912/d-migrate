package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Der Migrations-Pfad gegen echtes PostgreSQL: was der Reverse als kanonischen
 * Key ablegt (`tabelle::name`, `name(in:typ)`), muss als blanker Bezeichner in
 * die DDL gehen. Das `DROP` ist der schaerfere der beiden Faelle — es benennt
 * ein Objekt, das in der Datenbank steht.
 */
class PostgresDiffCanonicalKeyIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_keys")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var config: ConnectionConfig

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
        config = ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = container.host,
            port = container.firstMappedPort,
            database = "dmigrate_keys",
            user = "dmigrate",
            password = "dmigrate",
            ssl = SslSettings(SslMode.DISABLE),
        )
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE users (id INT PRIMARY KEY, updated_at TIMESTAMP)")
                stmt.execute(
                    """
                    CREATE FUNCTION touch_updated_at() RETURNS TRIGGER AS $$
                    BEGIN NEW.updated_at := now(); RETURN NEW; END;
                    $$ LANGUAGE plpgsql
                    """.trimIndent(),
                )
                stmt.execute(
                    "CREATE TRIGGER last_updated BEFORE UPDATE ON users " +
                        "FOR EACH ROW EXECUTE FUNCTION touch_updated_at()",
                )
            }
        }
    }

    afterSpec { container.stop() }

    test("a trigger read back under its canonical key is dropped by its bare name") {
        HikariConnectionPoolFactory.create(config).use { pool ->
            val current = PostgresSchemaReader().read(pool).schema
            // Vorbedingung: der Reverse legt den kanonischen Key ab.
            current.triggers.keys shouldBe setOf("users::last_updated")

            // Nur der Trigger: die Funktion mit abzuraeumen laeuft in eine
            // andere Baustelle, die Drop-Reihenfolge
            // ([`../open/pg-drop-order-routines-before-triggers.md`]).
            val desired = current.copy(triggers = emptyMap())
            val diff = SchemaComparator().compare(current, desired)
            val plan = DiffPlanner().plan(current, desired, diff)
            val migration = PostgresDiffDdlGenerator().generateUp(plan, DdlGenerationOptions())
            withClue(migration.blockers.joinToString()) { migration.blockers.shouldBeEmpty() }

            // Der eigentliche Beleg: der Server nimmt das DROP an. Mit dem Key
            // als Namen scheitert es mit „trigger … does not exist".
            pool.borrow().asJdbc().use { conn ->
                conn.createStatement().use { stmt ->
                    migration.statements.forEach { stmt.execute(it.sql) }
                }
            }

            PostgresSchemaReader().read(pool).schema.triggers.keys.shouldBeEmpty()
        }
    }
})
