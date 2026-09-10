package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.RawTextSandboxResult
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Der Wegwerf-Sandkasten gegen ein echtes PostgreSQL.
 *
 * Seine ganze Berechtigung ist eine Serverzusage: dass die Form IM Sandkasten
 * dieselbe ist wie die im Ziel. Das laesst sich nicht modellieren, nur messen —
 * und wenn es nicht stimmte, faellte der Sandkasten falsche Urteile.
 */
class PostgresRawTextSandboxIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:18-alpine")
        .withDatabaseName("dmigrate_test").withUsername("dmigrate").withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    beforeSpec {
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.POSTGRESQL,
                host = container.host, port = container.firstMappedPort,
                database = container.databaseName, user = container.username, password = container.password,
            ),
        )
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    val desired = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "status" to ColumnDefinition(NeutralType.Text(10)),
                    "nm" to ColumnDefinition(NeutralType.Text()),
                ),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "chk_status", type = ConstraintType.CHECK,
                        // Der Kommentar ist genau das, was der Server beim
                        // Zurueckgeben verliert.
                        expression = "status = 'A'   -- nur aktive\nAND nm <> ''",
                    ),
                ),
            ),
        ),
    )

    fun exec(sql: String) = pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute(sql) } }

    fun targetCheckForm(): String = pool.borrow().asJdbc().use { c ->
        c.createStatement().use { st ->
            st.executeQuery(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_status'",
            ).use { rs -> if (rs.next()) rs.getString(1) else "<keiner>" }
        }
    }

    test("der Sandkasten liefert dieselbe Form, die das Ziel danach wirklich fuehrt") {
        val sandboxed = PostgresRawTextSandbox().deparse(desired, pool)
            .shouldBeInstanceOf<RawTextSandboxResult.Deparsed>()
        val fromSandbox = sandboxed.schema.tables["orders"]!!.constraints.single().expression

        // Jetzt dasselbe Soll wirklich anwenden und die Katalogform vergleichen.
        exec(
            """CREATE TABLE orders (status varchar(10), nm text,
               CONSTRAINT chk_status CHECK (status = 'A'   -- nur aktive
               AND nm <> ''))""",
        )
        // Verglichen wird durch **denselben Leser**, durch den auch der
        // Migrationslauf beide Seiten sieht. Das ist die Aussage, auf die es
        // ankommt — und nicht die gegen `pg_get_constraintdef`: die beiden
        // Katalog-Drucker stimmten bis PostgreSQL 16 ueberein und tun es seit
        // 18 nicht mehr (eine Klammerebene Unterschied). Wer gegen den
        // fremden Drucker prueft, prueft eine Uebereinstimmung, die die
        // Produktion gar nicht braucht.
        val fromTarget = PostgresSchemaReader().read(pool).schema
            .tables.getValue("orders").constraints.single().expression

        withClue("Sandkasten='$fromSandbox' Ziel='$fromTarget'") {
            fromSandbox shouldBe fromTarget
        }
        // Die Gegenprobe, dass hier nicht zwei Ergebnisse derselben Abfrage
        // verglichen werden: der rohe Katalog-Drucker sagt es anders.
        withClue(targetCheckForm()) {
            targetCheckForm().removePrefix("CHECK ") shouldNotBe fromTarget
        }
        // Und der Kommentar ist auf beiden Seiten weg — das ist der Grund,
        // warum der Autorentext nie gegen die Katalogform stehen darf.
        withClue(fromSandbox.orEmpty()) { fromSandbox!!.contains("--") shouldBe false }
    }

    test("der Sandkasten hinterlaesst nichts — DDL ist bei PostgreSQL transaktional") {
        PostgresRawTextSandbox().deparse(desired, pool)

        val leftovers = pool.borrow().asJdbc().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(
                    "SELECT count(*) FROM information_schema.schemata WHERE schema_name LIKE 'dmg_sandbox_%'",
                ).use { rs -> rs.next(); rs.getInt(1) }
            }
        }

        leftovers shouldBe 0
    }

    test("was sich nicht anwenden laesst, nennt seinen Grund statt zu raten") {
        val broken = desired.copy(
            tables = mapOf(
                "orders" to desired.tables.getValue("orders").copy(
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_kaputt", type = ConstraintType.CHECK,
                            expression = "das ist kein SQL",
                        ),
                    ),
                ),
            ),
        )

        val result = PostgresRawTextSandbox().deparse(broken, pool)
            .shouldBeInstanceOf<RawTextSandboxResult.Unavailable>()

        withClue(result.reason) { result.reason.isNotBlank() shouldBe true }
    }
})
