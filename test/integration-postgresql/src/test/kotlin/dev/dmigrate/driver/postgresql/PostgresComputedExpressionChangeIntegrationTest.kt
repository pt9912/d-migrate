package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.RawTextAuthorship
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
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Ein geaenderter Berechnungsausdruck wird angewandt — und der Server rechnet
 * danach anders.
 *
 * Bis hierher endete das an zwei Stellen: `OperationMapper` bildete
 * `ColumnDiff.generation` gar nicht ab, und war die Aenderung belegt, blockte
 * der Lauf mit einer pauschalen Meldung. Ab PostgreSQL 17 gibt es
 * `ALTER COLUMN … SET EXPRESSION`; gemessen gegen 18.6 ueberleben dabei ein
 * Index auf der Spalte und eine abhaengige Sicht, und der gespeicherte Wert
 * entsteht neu.
 *
 * Die Herkunft wird hier als Doppel gesetzt: **Gegenstand dieser Spec ist das
 * Anwenden**, nicht der Herkunftsmechanismus — der hat seine eigenen Tests.
 * Ohne ein solches Urteil faltet der Vergleich den Ausdruck bewusst weg, und
 * es gaebe nichts zu planen.
 */
class PostgresComputedExpressionChangeIntegrationTest : FunSpec({

    val container = PostgreSQLContainer(TestImages.POSTGRESQL)
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

    fun schemaWith(expression: String) = SchemaDefinition(
        name = "pg_computed_change", version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "quantity" to ColumnDefinition(NeutralType.Integer, required = true),
                    "unit_price" to ColumnDefinition(NeutralType.Decimal(12, 2), required = true),
                    "line_total" to ColumnDefinition(
                        NeutralType.Decimal(14, 2),
                        generation = ColumnGeneration.Computed(expression, stored = true),
                    ),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    /** Ein Lauf; `authorChanged` steht fuer das Urteil der Herkunft. */
    fun migrate(expression: String, authorChanged: Boolean): Pair<Int, List<String>> {
        val tmp = createTempDirectory("pg-computed-change")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", schemaWith(expression), ValidationResult()) },
                dbLoader = { _, _ ->
                    val read = PostgresSchemaReader().read(pool)
                    ResolvedSchemaOperand(
                        reference = "live-pg",
                        schema = read.schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL,
                        serverVersion = read.serverVersion,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, _, serverForm ->
                    SchemaComparator(
                        projection,
                        RawTextAuthorship { _, _, _, _, _ -> authorChanged },
                        serverForm,
                    ).compare(l, r)
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
            exit to executed
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun lineTotalFor(id: Int, quantity: Int, price: String): String? {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use {
                it.execute("INSERT INTO order_line (id, quantity, unit_price) VALUES ($id, $quantity, $price)")
            }
            return conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT line_total FROM order_line WHERE id = $id").use { rs ->
                    if (rs.next()) rs.getBigDecimal(1).toPlainString() else null
                }
            }
        }
    }

    test("a changed expression is applied and the server computes by the new formula") {
        migrate("quantity * unit_price", authorChanged = false)
        lineTotalFor(id = 1, quantity = 3, price = "7.00") shouldBe "21.00"

        val (_, statements) = migrate("quantity * unit_price * 2", authorChanged = true)

        withClue(statements.joinToString("; ")) {
            statements.count { it.contains("SET EXPRESSION") } shouldBe 1
        }
        // Der bestehende Wert wird neu berechnet, nicht nur der naechste.
        lineTotalFor(id = 2, quantity = 3, price = "7.00") shouldBe "42.00"
    }
})
