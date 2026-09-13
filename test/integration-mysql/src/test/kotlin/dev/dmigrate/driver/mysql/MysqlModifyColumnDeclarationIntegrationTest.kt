package dev.dmigrate.driver.mysql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.NeutralType
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
import org.testcontainers.mysql.MySQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Eine Typaenderung auf MySQL nimmt der Spalte nicht mehr ihre uebrigen
 * Eigenschaften.
 *
 * **Warum das eine eigene Spec braucht.** `MODIFY COLUMN` ersetzt die **ganze**
 * Deklaration. Der Renderer nannte frueher nur Name und Typ, mit der
 * Begruendung, die Operation aendere ja auch nur den Typ — das schuetzt die
 * Spalte aber nicht. Live gegen 9.7.2 gemessen fielen dabei still weg:
 * `NOT NULL`, der `DEFAULT` und der `AUTO_INCREMENT`. Der Post-Compare meldete
 * es als Drift — **nachdem** die Anweisung angewandt war.
 */
class MysqlModifyColumnDeclarationIntegrationTest : FunSpec({

    val container = MySQLContainer(TestImages.MYSQL)
        .withDatabaseName("modify_decl").withUsername("dmigrate").withPassword("dmigrate")

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
                    "CREATE TABLE orders (" +
                        "id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, " +
                        "qty INT NOT NULL, " +
                        "label VARCHAR(20) NOT NULL DEFAULT 'x'" +
                        ") ENGINE=InnoDB",
                )
            }
        }
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
        DatabaseDriverRegistry.clear()
    }

    fun liveSchema(): SchemaDefinition = MysqlSchemaReader().read(pool).schema

    /** Das Ist mit genau einem geaenderten Spaltentyp. */
    fun desiredWithType(column: String, type: NeutralType): SchemaDefinition {
        val live = liveSchema()
        val table = live.tables.getValue("orders")
        val definition = table.columns.getValue(column)
        return live.copy(
            tables = live.tables + (
                "orders" to table.copy(
                    columns = LinkedHashMap(table.columns).also { it[column] = definition.copy(type = type) },
                )
                ),
        )
    }

    fun migrate(want: SchemaDefinition): Triple<Int, List<String>, String> {
        val tmp = createTempDirectory("mysql-modify-declaration")
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
                    SchemaComparator(projection, null, serverForm).compare(l, r)
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
            Triple(exit, executed, errors.joinToString("; "))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun describe(column: String): String = pool.borrow().asJdbc().use { conn ->
        conn.prepareStatement(
            "SELECT column_type, is_nullable, column_default, extra FROM information_schema.columns " +
                "WHERE table_schema = DATABASE() AND table_name = 'orders' AND column_name = ?",
        ).use { ps ->
            ps.setString(1, column)
            ps.executeQuery().use { rs ->
                if (!rs.next()) "keine Zeile"
                else "${rs.getString(1)} nullable=${rs.getString(2)} default=${rs.getString(3)} extra='${rs.getString(4)}'"
            }
        }
    }

    test("widening a NOT NULL column keeps NOT NULL") {
        val (exit, executed, errors) = migrate(desiredWithType("qty", NeutralType.BigInteger))

        withClue(errors + " || " + executed.joinToString(" | ")) { exit shouldBe 0 }
        describe("qty") shouldBe "bigint nullable=NO default=null extra=''"
    }

    test("widening a column with a DEFAULT keeps the DEFAULT") {
        val (exit, executed, errors) = migrate(desiredWithType("label", NeutralType.Text(30)))

        withClue(errors + " || " + executed.joinToString(" | ")) { exit shouldBe 0 }
        withClue(describe("label")) {
            describe("label").contains("nullable=NO") shouldBe true
            describe("label").contains("default=x") shouldBe true
        }
    }

    /**
     * Die Verbreiterung einer Autowert-Spalte wird als `biginteger` **mit**
     * `generation: identity` geschrieben — das ist der dokumentierte Weg, und
     * `Identifier` → `BigInteger` allein ist auf MySQL kein zulaessiger
     * impliziter Cast (der Lauf blockt dann, zu Recht).
     */
    test("widening the identity column keeps AUTO_INCREMENT") {
        val live = liveSchema()
        val table = live.tables.getValue("orders")
        val idColumn = table.columns.getValue("id")
        val want = live.copy(
            tables = live.tables + (
                "orders" to table.copy(
                    columns = LinkedHashMap(table.columns).also {
                        // `legacySerialSyntax = true` ist die Form, die der
                        // MySQL-Reverse selbst liefert (`AUTO_INCREMENT` ist dort
                        // die Alt-Schreibweise) — und damit die, die im
                        // Reverse-dann-Bearbeiten-Weg im Soll steht.
                        it["id"] = idColumn.copy(
                            type = NeutralType.BigInteger,
                            generation = dev.dmigrate.core.model.ColumnGeneration.Identity(
                                legacySerialSyntax = true,
                            ),
                        )
                    },
                )
                ),
        )
        val (exit, executed, errors) = migrate(want)

        withClue(errors + " || " + executed.joinToString(" | ")) { exit shouldBe 0 }
        withClue(describe("id")) {
            describe("id").contains("auto_increment") shouldBe true
            describe("id").contains("bigint") shouldBe true
        }
    }
})
