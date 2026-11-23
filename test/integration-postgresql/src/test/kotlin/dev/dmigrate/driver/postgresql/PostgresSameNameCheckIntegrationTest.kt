package dev.dmigrate.driver.postgresql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * Issue #77 gegen echtes PostgreSQL: zwei Tabellen tragen je einen CHECK
 * **gleichen Namens** mit verschiedenem Ausdruck. PostgreSQL erlaubt das, ein
 * CHECK-Name ist nur je Tabelle eindeutig.
 *
 * Der Reader verband `information_schema.table_constraints` und
 * `check_constraints` nur ueber Name und Schema. Jede Tabelle bekam dadurch
 * die Ausdruecke aller gleichnamigen CHECKs, und einer davon gewann. Folge:
 * `schema compare` meldete an `t_a` den Ausdruck von `t_b`, und `schema
 * migrate --target db:` plante bei jedem Lauf DROP/ADD fuer beide Tabellen.
 */
class PostgresSameNameCheckIntegrationTest : FunSpec({

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

    fun table(values: List<String>) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "state" to ColumnDefinition(NeutralType.Text()),
        ),
        primaryKey = listOf("id"),
        constraints = listOf(
            ConstraintDefinition(
                name = "ck_state",
                type = ConstraintType.CHECK,
                expression = "state IN (" + values.joinToString(", ") { "'$it'" } + ")",
            ),
        ),
    )

    val desired = SchemaDefinition(
        name = "repro", version = "1.0.0",
        tables = linkedMapOf(
            "t_a" to table(listOf("ACTIVE", "DISABLED")),
            "t_b" to table(listOf("OFF", "ON")),
        ),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert die ausgefuehrten Anweisungen. */
    fun migrate(want: SchemaDefinition): List<String> {
        val tmp = createTempDirectory("pg-same-name-check")
        try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
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

    fun readCheck(table: String): String {
        val checks = PostgresSchemaReader().read(pool).schema.tables.getValue(table).constraints
            .filter { it.type == ConstraintType.CHECK }
        withClue("$table: $checks") { checks.map { it.name } shouldBe listOf("ck_state") }
        return checks.single().expression.orEmpty()
    }

    test("der Reader ordnet gleichnamige CHECKs ihrer eigenen Tabelle zu") {
        migrate(desired)

        val a = readCheck("t_a")
        val b = readCheck("t_b")
        a shouldContain "'ACTIVE'"
        a shouldNotContain "'OFF'"
        b shouldContain "'OFF'"
        b shouldNotContain "'ACTIVE'"
    }

    test("ein zweiter Lauf gegen die so erzeugte Datenbank plant nichts") {
        migrate(desired)

        migrate(desired) shouldBe emptyList()
    }

    test("eine geaenderte Liste an einer Tabelle ersetzt nur deren CHECK") {
        migrate(desired)
        val changed = desired.copy(
            tables = linkedMapOf("t_a" to table(listOf("ACTIVE", "DISABLED", "LOCKED")), "t_b" to table(listOf("OFF", "ON"))),
        )

        val statements = migrate(changed)

        withClue(statements.toString()) {
            statements.size shouldBe 2
            statements.all { it.contains("\"t_a\"") } shouldBe true
        }
        readCheck("t_a") shouldContain "'LOCKED'"
        readCheck("t_b") shouldNotContain "'LOCKED'"
        migrate(changed) shouldBe emptyList()
    }
})
