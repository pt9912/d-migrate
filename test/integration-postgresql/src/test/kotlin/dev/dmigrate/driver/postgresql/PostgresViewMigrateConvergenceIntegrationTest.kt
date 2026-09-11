package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.ViewDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Ein Lauf, der eine Sicht anlegt, endet sauber — und nicht mit Drift.
 *
 * Gemeldet aus einem Konsumentenprojekt gegen 1.3.0: der erste
 * `schema migrate --execute` legt die Sichten an, fuehrt alle Anweisungen aus
 * — und endet dann mit Exit 5, „the target does not match the desired
 * schema". Ein CHECK-Ausdruck im selben Lauf konvergiert dabei sauber; nur
 * Sichten tun es nicht.
 *
 * Der Post-Compare blendet rohen SQL-Text auf beiden Seiten aus, bevor er den
 * Fingerabdruck bildet — den Rumpf der Sicht also. Was er nicht ausblendet,
 * ist `sourceDialect`: die Datei traegt es nicht, der Rueckleser setzt es.
 * Damit unterscheiden sich die Fingerabdruecke, und der Lauf meldet Drift, wo
 * keine ist.
 *
 * Die Folge waere sonst dauerhaft: Herkunft entsteht nur bei sauberem
 * Post-Compare, also entsteht sie fuer Sichten nie, und der naechste Lauf
 * plant dasselbe erneut.
 */
class PostgresViewMigrateConvergenceIntegrationTest : FunSpec({

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

    val desired = SchemaDefinition(
        name = "pg_view", version = "1",
        tables = mapOf(
            "source_table" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "label" to ColumnDefinition(NeutralType.Text(50), required = true),
                ),
                primaryKey = listOf("id"),
            ),
        ),
        views = mapOf(
            "active_rows" to ViewDefinition(query = "SELECT id, label FROM source_table"),
        ),
    )

    fun migrate(): Int {
        val tmp = createTempDirectory("pg-view")
        return try {
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", desired, ValidationResult()) },
                dbLoader = { _, _ ->
                    ResolvedSchemaOperand(
                        reference = "live-pg",
                        schema = PostgresSchemaReader().read(pool).schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.POSTGRESQL,
                    )
                },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.POSTGRESQL) PostgresDiffDdlGenerator() else null },
                executor = { _, _, segments, _, _ ->
                    executeAgainstPool(pool, segments.flatMap { it.statements })
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
            withClue("Post-Compare meldete: " + errors.joinToString("; ")) { exit shouldBe 0 }
            exit
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a run that creates a view ends clean instead of reporting drift") {
        migrate()
    }
})
