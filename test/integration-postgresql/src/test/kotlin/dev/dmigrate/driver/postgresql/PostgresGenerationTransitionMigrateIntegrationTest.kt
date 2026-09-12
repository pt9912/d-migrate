package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.RawTextAuthorship
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.SchemaDefinition
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
import io.kotest.matchers.string.shouldContain
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Eine berechnete Spalte wird gewoehnlich — auf PostgreSQL durch den ganzen
 * Migrationspfad, nicht nur im Renderer.
 *
 * **Warum der Vollpfad und nicht die Anweisung allein.** Auf Oracle wurde
 * dasselbe `MODIFY` vom Server **angenommen** und aenderte trotzdem nichts; erst
 * der Post-Compare zeigte, dass die Spalte weiter virtuell war. Eine
 * angenommene Anweisung ist noch keine wirksame — also wird hier zurueckgelesen
 * und geschrieben, nicht nur abgeschickt.
 *
 * Gemessen gegen 18.6: `DROP EXPRESSION` laesst den gespeicherten Wert als
 * gewoehnliche Daten stehen, und die Spalte ist danach beschreibbar.
 */
class PostgresGenerationTransitionMigrateIntegrationTest : FunSpec({

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
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE computed_drop (" +
                        "id int PRIMARY KEY, qty int NOT NULL, " +
                        "total int GENERATED ALWAYS AS (qty * 2) STORED)",
                )
                stmt.execute("INSERT INTO computed_drop (id, qty) VALUES (1, 21)")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
        DatabaseDriverRegistry.clear()
    }

    fun liveSchema(): SchemaDefinition = PostgresSchemaReader().read(pool).schema

    fun desiredWith(table: String, column: String, generation: ColumnGeneration?): SchemaDefinition {
        val live = liveSchema()
        val definition = live.tables.getValue(table)
        val column0 = definition.columns.getValue(column)
        return live.copy(
            tables = live.tables + (
                table to definition.copy(
                    columns = LinkedHashMap(definition.columns).also {
                        it[column] = column0.copy(generation = generation)
                    },
                )
                ),
        )
    }

    fun migrate(want: SchemaDefinition, changedTable: String?): Triple<Int, List<String>, String> {
        val tmp = createTempDirectory("pg-generation-transition")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
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
                        RawTextAuthorship { _, path, _, _, _ -> path.firstOrNull() == changedTable },
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
            Triple(exit, executed, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a computed column becomes an ordinary one, keeping the value it had") {
        val want = desiredWith("computed_drop", "total", null)

        val (exit, executed, errors) = migrate(want, changedTable = "computed_drop")

        withClue(errors) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.single() shouldContain "ALTER COLUMN \"total\" DROP EXPRESSION"
        }

        // Der Post-Compare hat schon zugestimmt; hier noch das, was ein
        // Anwender merkt: der Wert steht, und die Spalte ist beschreibbar.
        liveSchema().tables.getValue("computed_drop").columns.getValue("total").generation shouldBe null
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT total FROM computed_drop WHERE id = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 42
                }
            }
            conn.createStatement().use { it.execute("UPDATE computed_drop SET total = 7 WHERE id = 1") }
        }
    }
})
