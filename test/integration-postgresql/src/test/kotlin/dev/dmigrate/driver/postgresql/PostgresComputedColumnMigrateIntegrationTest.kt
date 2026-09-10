package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Eine berechnete Spalte ueberlebt den Weg durch `schema migrate` — und der
 * Lauf konvergiert.
 *
 * Bis das neutrale Modell eine Form dafuer hatte, ging die Zusicherung
 * „`line_total` ist immer `quantity * unit_price`" beim Zuruecklesen verloren.
 * Diese Spec faehrt den ECHTEN Runner und prueft am Ende den Server selbst:
 * eine Zeile einfuegen, ohne die Spalte zu nennen, und nachsehen, ob er
 * gerechnet hat.
 */
class PostgresComputedColumnMigrateIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:16-alpine")
    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(PostgresDriver())
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

    val desired = SchemaDefinition(
        name = "pg_computed", version = "1",
        tables = mapOf("order_line" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
                "line_total" to ColumnDefinition(
                    NeutralType.Decimal(14, 2),
                    generation = ColumnGeneration.Computed("quantity * unit_price", stored = true),
                ),
            ),
            primaryKey = listOf("id"),
        )),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert die ausgefuehrten Anweisungen. */
    fun migrate(): List<String> {
        val tmp = createTempDirectory("pg-computed")
        try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", desired, ValidationResult()) },
                dbLoader = { _, _ -> ResolvedSchemaOperand(
                    reference = "live-pg",
                    schema = PostgresSchemaReader().read(pool).schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                ) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    executed += stmts.map { it.sql }
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.POSTGRESQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue(errors.joinToString("; ")) { exit shouldBe 0 }
            return executed
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("the computed column is created, read back, and the run converges") {
        migrate()

        val column = PostgresSchemaReader().read(pool).schema
            .tables.getValue("order_line").columns.getValue("line_total")
        val generation = column.generation as? ColumnGeneration.Computed
        withClue(column.generation.toString()) {
            generation.shouldNotBeNull()
            // PostgreSQL kennt nur die gespeicherte Form.
            generation.stored shouldBe true
            // Zurueck kommt die Normalform des Servers, nicht der Autorentext —
            // genau deshalb faellt der Ausdruck aus dem Textvergleich heraus.
            generation.expression shouldBe "((quantity)::numeric * unit_price)"
        }

        // Der zweite Lauf plant nichts: der Ausdruck ist nicht entscheidbar,
        // also wird er nicht verglichen — und alles andere stimmt ueberein.
        migrate() shouldBe emptyList()
    }

    test("the server really computes the value") {
        migrate()
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use {
                it.execute("INSERT INTO order_line (id, quantity, unit_price) VALUES (7, 3, 7.00)")
            }
            val total = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT line_total FROM order_line WHERE id = 7").use { rs ->
                    if (rs.next()) rs.getBigDecimal(1).toPlainString() else null
                }
            }
            withClue("ohne diese Zusicherung waere die Berechnung nur Text im Katalog") {
                total shouldBe "21.00"
            }
        }
    }
})
