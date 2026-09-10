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
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.SQLException
import kotlin.io.path.createTempDirectory

/**
 * Eine Enum-Spalte laesst `schema migrate` gegen PostgreSQL konvergieren, und
 * ihr Wertevorrat wird durchgesetzt.
 *
 * Ein Inline-Enum (Werte am Spaltentyp, kein eigener Typ) wird in PostgreSQL zu
 * einer Textspalte mit einem CHECK darueber. Rendert der Migrationspfad den
 * CHECK nicht, traegt das Ziel den Wertevorrat nirgends — und der Vergleich
 * meldet nach **jedem** Lauf Drift, obwohl der Lauf getan hat, was verlangt war.
 *
 * Eine geaenderte Werteliste laeuft ueber den ALTER-Pfad, der den alten CHECK
 * unter seinem echten Katalognamen loest und den neuen anlegt. Ein erratener
 * Name traefe ihn nicht: PostgreSQL benennt einen unbenannten Constraint selbst,
 * haengt bei Kollision eine Ziffer an und kuerzt lange Namen.
 */
class PostgresEnumMigrateConvergenceIntegrationTest : FunSpec({

    val container = PostgreSQLContainer("postgres:18-alpine")
    lateinit var pool: ConnectionPool

    beforeSpec {
        // Der Runner loest die Typ-Kanonisierung des Ziels ueber die Registry
        // auf und faellt ohne Eintrag still auf die Identitaet zurueck — dann
        // pruefte diese Spec eine Projektion, die im Betrieb eine andere ist.
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
        name = "pg_enum", version = "1",
        tables = mapOf("mood_probe" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                "mood" to ColumnDefinition(NeutralType.Enum(values = values)),
            ),
            primaryKey = listOf("id"),
        )),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert die ausgefuehrten Anweisungen. */
    fun migrate(want: SchemaDefinition): List<String> {
        val tmp = createTempDirectory("pg-enum-convergence")
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

    fun checkExpressions(): List<String> = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con " +
                    "JOIN pg_class rel ON rel.oid = con.conrelid " +
                    "WHERE con.contype = 'c' AND rel.relname = 'mood_probe'",
            ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    fun insertMood(value: String): Boolean = runCatching {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO mood_probe (id, mood) VALUES (1, '$value')") }
        }
    }.also { result ->
        // Ein anderer Fehlschlag als der CHECK waere ein falscher Beleg.
        result.exceptionOrNull()?.let { e -> if (e !is SQLException) throw e }
    }.isSuccess

    test("the first migrate creates the column with the CHECK that enforces its values") {
        migrate(schemaWith(listOf("red", "green")))

        withClue(checkExpressions().toString()) {
            checkExpressions().any { it.contains("'red'") && it.contains("'green'") } shouldBe true
        }
        withClue("ohne diese Zusicherung waere der CHECK nur Text im Katalog") {
            insertMood("blue") shouldBe false
        }
    }

    test("a second migrate against the converged schema plans nothing") {
        migrate(schemaWith(listOf("red", "green")))

        migrate(schemaWith(listOf("red", "green"))) shouldBe emptyList()
    }

    test("a changed vocabulary replaces the CHECK and converges again") {
        migrate(schemaWith(listOf("red", "green")))

        val statements = migrate(schemaWith(listOf("red", "brown")))

        withClue(statements.toString()) {
            // Genau zwei: der alte CHECK weg, der neue hin. Kein Typwechsel —
            // die Spalte ist auf beiden Seiten TEXT.
            statements.size shouldBe 2
            statements.any { it.contains("DROP CONSTRAINT") } shouldBe true
            statements.any { it.contains("ADD CHECK") } shouldBe true
            statements.none { it.contains("ALTER COLUMN") && it.contains("TYPE") } shouldBe true
        }
        withClue(checkExpressions().toString()) {
            checkExpressions().any { it.contains("'brown'") } shouldBe true
            checkExpressions().none { it.contains("'green'") } shouldBe true
        }
        // Und danach ist wieder Ruhe.
        migrate(schemaWith(listOf("red", "brown"))) shouldBe emptyList()
    }
})
