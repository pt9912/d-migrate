package dev.dmigrate.driver.mysql

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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.testcontainers.mysql.MySQLContainer
import kotlin.io.path.createTempDirectory

/**
 * `column-generation-diff-unmapped.md`: der Kind-Wechsel (gewoehnlich ↔
 * berechnet) auf MySQL, durch die ganze Migrationspipeline. MySQL lehnt
 * beide Richtungen in place ab (`'Changing the STORED status' is not
 * supported for generated columns'`, gemessen gegen 9.7.2); seitdem laufen
 * sie ueber den Spaltentausch.
 */
class MysqlGenerationTransitionMigrateIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("gen_transition").withUsername("dmigrate").withPassword("dmigrate")

    lateinit var pool: ConnectionPool

    beforeSpec {
        DatabaseDriverRegistry.register(MysqlDriver())
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
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE computed (id INT NOT NULL PRIMARY KEY, qty INT NOT NULL, " +
                        "total INT GENERATED ALWAYS AS (qty * 2) STORED) ENGINE=InnoDB",
                )
                stmt.execute("INSERT INTO computed (id, qty) VALUES (1, 21)")
                stmt.execute(
                    "CREATE TABLE plain_calc (id INT NOT NULL PRIMARY KEY, qty INT NOT NULL, total INT) ENGINE=InnoDB",
                )
                stmt.execute("INSERT INTO plain_calc (id, qty, total) VALUES (1, 5, 999)")
                stmt.execute("CREATE TABLE keyed_calc (id INT NOT NULL PRIMARY KEY, qty INT NOT NULL) ENGINE=InnoDB")
                stmt.execute("INSERT INTO keyed_calc (id, qty) VALUES (1, 5)")
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
        DatabaseDriverRegistry.clear()
    }

    fun liveSchema(): SchemaDefinition = MysqlSchemaReader().read(pool).schema

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
        val tmp = createTempDirectory("mysql-generation-transition")
        return try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ ->
                    val read = MysqlSchemaReader().read(pool)
                    ResolvedSchemaOperand(
                        reference = "live-mysql",
                        schema = read.schema,
                        validation = ValidationResult(),
                        dialect = DatabaseDialect.MYSQL,
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
                    allowDestructive = true,
                ),
            )
            Triple(exit, executed, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    test("a computed column becomes ordinary via swap, keeping its frozen value") {
        val want = desiredWith("computed", "total", null)
        val (exit, executed, errors) = migrate(want, changedTable = "computed")

        withClue(errors) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.size shouldBe 5
            executed[0] shouldContain "ADD COLUMN `total__dmg_swap`"
            executed[1] shouldContain "UPDATE"
            executed[2] shouldContain "DROP COLUMN `total`"
            executed[3] shouldContain "RENAME COLUMN `total__dmg_swap` TO `total`"
            executed[4] shouldContain "MODIFY COLUMN `total`"
        }
        liveSchema().tables.getValue("computed").columns.getValue("total").generation shouldBe null
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT total FROM computed WHERE id = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 42
                }
            }
            conn.createStatement().use { it.execute("UPDATE computed SET total = 7 WHERE id = 1") }
        }
    }

    test("an ordinary column becomes computed via swap, with no encumbrance blocking it") {
        val want = desiredWith("plain_calc", "total", ColumnGeneration.Computed("qty * 2", stored = true))
        val (exit, executed, errors) = migrate(want, changedTable = "plain_calc")

        withClue(errors) { exit shouldBe 0 }
        withClue(executed.joinToString(" | ")) {
            executed.size shouldBe 2
            executed[0] shouldContain "DROP COLUMN `total`"
            executed[1] shouldContain "ADD COLUMN `total`"
        }
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT total FROM plain_calc WHERE id = 1").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 10
                }
            }
        }
    }

    test("an encumbered column (part of the primary key) blocks the swap with a precise reason") {
        val want = desiredWith("keyed_calc", "id", ColumnGeneration.Computed("qty * 2", stored = true))
        val (exit, executed, _) = migrate(want, changedTable = "keyed_calc")

        exit shouldBe 8
        executed.shouldBeEmpty()
    }
})
