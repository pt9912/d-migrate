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
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.test.images.TestImages
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

/**
 * Issue #76 gegen echtes PostgreSQL: eine Textspalte, deren Wertevorrat ein
 * **benannter** `IN`-CHECK im Soll aufzaehlt — nicht ein `enum` am Spaltentyp.
 *
 * Vor dem Fix faltete der Vergleich den CHECK in die Spalte; eine geaenderte
 * Werteliste wurde zur Spaltenaenderung ohne Werteliste, der Up loeste den alten
 * CHECK und legte keinen neuen an. Danach nahm die Spalte jeden Text an.
 *
 * Gemessen wird auch die Konvergenz: PostgreSQL liest `IN (...)` als
 * `= ANY (ARRAY[...])` zurueck. Gleiche Werte in beiden Schreibweisen duerfen
 * keine Operation ausloesen.
 */
class PostgresNamedValueCheckMigrateIntegrationTest : FunSpec({

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

    fun schemaWith(values: List<String>) = SchemaDefinition(
        name = "pg_named_check", version = "1",
        tables = mapOf("obj_account" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                "a_state" to ColumnDefinition(NeutralType.Text()),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ck_0123456789abcdef",
                    type = ConstraintType.CHECK,
                    expression = "a_state IN (" + values.joinToString(", ") { "'$it'" } + ")",
                ),
            ),
        )),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert die ausgefuehrten Anweisungen. */
    fun migrate(want: SchemaDefinition): List<String> {
        val tmp = createTempDirectory("pg-named-check")
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

    fun checks(): Map<String, String> = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT con.conname, pg_get_constraintdef(con.oid) FROM pg_constraint con " +
                    "JOIN pg_class rel ON rel.oid = con.conrelid " +
                    "WHERE con.contype = 'c' AND rel.relname = 'obj_account'",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) to rs.getString(2) else null }.toMap() }
        }
    }

    fun accepts(id: Int, value: String): Boolean = runCatching {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use {
                it.execute("INSERT INTO obj_account (id, a_state) VALUES ($id, '$value')")
            }
        }
    }.also { result ->
        // Ein anderer Fehlschlag als der CHECK waere ein falscher Beleg.
        result.exceptionOrNull()?.let { e -> if (e !is SQLException) throw e }
    }.isSuccess

    test("the named CHECK is created and a second run plans nothing") {
        migrate(schemaWith(listOf("ACTIVE", "DISABLED")))

        checks().keys shouldBe setOf("ck_0123456789abcdef")
        migrate(schemaWith(listOf("ACTIVE", "DISABLED"))) shouldBe emptyList()
    }

    test("a widened list replaces the CHECK under its name, enforces it, and converges") {
        migrate(schemaWith(listOf("ACTIVE", "DISABLED")))

        val statements = migrate(schemaWith(listOf("ACTIVE", "DISABLED", "LOCKED")))

        withClue(statements.toString()) {
            statements.count { it.contains("DROP CONSTRAINT") } shouldBe 1
            statements.count { it.contains("ADD CONSTRAINT \"ck_0123456789abcdef\"") } shouldBe 1
        }
        withClue(checks().toString()) {
            checks().keys shouldBe setOf("ck_0123456789abcdef")
            checks().values.single().contains("'LOCKED'") shouldBe true
        }
        withClue("die Spalte muss den neuen Wert annehmen und einen fremden weiter abweisen") {
            accepts(1, "LOCKED") shouldBe true
            accepts(2, "WHATEVER") shouldBe false
        }
        migrate(schemaWith(listOf("ACTIVE", "DISABLED", "LOCKED"))) shouldBe emptyList()
    }

    test("a shortened list replaces the CHECK as well") {
        pool.borrow().asJdbc().use { conn -> conn.createStatement().use { it.execute("DELETE FROM obj_account") } }
        migrate(schemaWith(listOf("ACTIVE", "DISABLED", "LOCKED")))

        migrate(schemaWith(listOf("ACTIVE")))

        withClue(checks().toString()) {
            checks().keys shouldBe setOf("ck_0123456789abcdef")
            checks().values.single().contains("'DISABLED'") shouldBe false
        }
        accepts(3, "DISABLED") shouldBe false
        migrate(schemaWith(listOf("ACTIVE"))) shouldBe emptyList()
    }
})
