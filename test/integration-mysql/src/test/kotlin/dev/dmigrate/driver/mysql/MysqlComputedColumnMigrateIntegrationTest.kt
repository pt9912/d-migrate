package dev.dmigrate.driver.mysql

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
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.MySQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Eine berechnete Spalte auf MySQL: angelegt, zurueckgelesen, geaendert.
 *
 * Der Leser meldete sie bisher als Verlust (`Computed column definition … is
 * not carried in the neutral model`), obwohl `information_schema` beides
 * fuehrt — `EXTRA` die Speicherform, `GENERATION_EXPRESSION` den Ausdruck.
 *
 * Gemessen gegen 9.7.2, bevor hier etwas gebaut wurde: `MODIFY COLUMN …
 * GENERATED ALWAYS AS (…)` laeuft in place, ein Index auf der Spalte und eine
 * abhaengige Sicht ueberleben, und der gespeicherte Wert entsteht neu.
 */
class MysqlComputedColumnMigrateIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    lateinit var pool: ConnectionPool

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
        runCatching { pool.close() }
        container.stop()
    }

    fun schemaWith(expression: String) = SchemaDefinition(
        name = "mysql_computed", version = "1",
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

    fun migrate(expression: String, authorChanged: Boolean): List<String> {
        val tmp = createTempDirectory("mysql-computed")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", schemaWith(expression), ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-mysql",
                        schema = MysqlSchemaReader().read(pool, SchemaReadOptions()).schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.MYSQL,
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
                rendererFor = { d -> if (d == DatabaseDialect.MYSQL) MysqlDiffDdlGenerator() else null },
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
                    dialect = DatabaseDialect.MYSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue(errors.joinToString("; ")) { exit shouldBe 0 }
            executed
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

    test("the computed column is created and read back as computed") {
        migrate("quantity * unit_price", authorChanged = false)

        val column = MysqlSchemaReader().read(pool, SchemaReadOptions()).schema
            .tables.getValue("order_line").columns.getValue("line_total")
        val generation = column.generation as? ColumnGeneration.Computed
        withClue(column.generation.toString()) {
            generation.shouldNotBeNull()
            generation.stored shouldBe true
        }
        lineTotalFor(id = 1, quantity = 3, price = "7.00") shouldBe "21.00"
    }

    test("a changed expression is applied in place and the server recomputes") {
        val statements = migrate("quantity * unit_price * 2", authorChanged = true)

        withClue(statements.joinToString("; ")) {
            statements.count { it.contains("MODIFY COLUMN") && it.contains("GENERATED ALWAYS AS") } shouldBe 1
        }
        lineTotalFor(id = 2, quantity = 3, price = "7.00") shouldBe "42.00"
    }
})
