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
import io.kotest.matchers.string.shouldContain
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.io.path.createTempDirectory

/**
 * ADR 0068 gegen echtes PostgreSQL: eine Werteliste an einer `varchar`-Spalte. PostgreSQL gibt
 * `a_state IN ('OFF','ON')` dort als `((a_state)::text = ANY ((ARRAY[...])::text[]))` zurueck. Vor ADR 0068
 * erkannte die Werteliste-Erkennung diese Form nicht: `schema migrate --target db:` plante gegen eine aus
 * derselben Datei erzeugte Datenbank bei jedem Lauf DROP/ADD, und `schema compare` meldete den gleichen
 * Wertevorrat als Aenderung.
 */
class PostgresVarcharValueListIntegrationTest : FunSpec({

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

    fun schemaWith(values: List<String>, maxLength: Int? = 20) = SchemaDefinition(
        name = "repro", version = "1.0.0",
        tables = mapOf("obj_device" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "a_state" to ColumnDefinition(NeutralType.Text(maxLength = maxLength)),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ck_a_state", type = ConstraintType.CHECK,
                    expression = "a_state IN (" + values.joinToString(", ") { "'$it'" } + ")",
                ),
            ),
        )),
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

    fun dropTable() = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { it.execute("DROP TABLE IF EXISTS obj_device") }
    }

    fun catalogCheck(): String = pool.borrow().asJdbc().use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT pg_get_expr(conbin, conrelid) FROM pg_constraint WHERE conname = 'ck_a_state'",
            ).use { rs -> rs.next(); rs.getString(1) }
        }
    }

    // `text` ohne `max_length` rendert als `TEXT`; dessen Rueckform (`… = ANY (ARRAY[…'::text])`) kannte die
    // Erkennung schon. Der Fall steht als Kontrolle daneben.
    for ((label, maxLength, form) in listOf(
        Triple("varchar(20)", 20, "::text[]"),
        Triple("text (Kontrolle)", null, "ARRAY['OFF'::text"),
    )) {
        test("$label: angelegt, zweiter Lauf plant nichts, compare gleich") {
            dropTable()
            val desired = schemaWith(listOf("OFF", "ON"), maxLength = maxLength)
            migrate(desired)
            withClue("so gibt PostgreSQL die Liste zurueck") { catalogCheck() shouldContain form }

            migrate(desired) shouldBe emptyList()
            val live = PostgresSchemaReader().read(pool).schema
            // Wie `schema compare`: die Dialekt-Schreibweise gefaltet (ADR 0056/0067). Gemessen wird nur
            // der CHECK; Name und Version des gelesenen Schemas sind hier nicht Gegenstand.
            val tables = SchemaComparator(canonicalizeRawExpressions = true).compare(desired, live).tablesChanged
            withClue(tables.toString()) {
                tables.flatMap { it.constraintsChanged + it.constraintsAdded + it.constraintsRemoved } shouldBe emptyList()
            }
        }
    }

    test("eine geaenderte Liste ersetzt den CHECK unter seinem Namen und konvergiert") {
        dropTable()
        migrate(schemaWith(listOf("OFF", "ON")))

        val statements = migrate(schemaWith(listOf("OFF", "ON", "STANDBY")))

        withClue(statements.toString()) {
            statements.count { it.contains("DROP CONSTRAINT") } shouldBe 1
            statements.count { it.contains("ADD CONSTRAINT \"ck_a_state\"") } shouldBe 1
        }
        catalogCheck() shouldContain "'STANDBY'"
        migrate(schemaWith(listOf("OFF", "ON", "STANDBY"))) shouldBe emptyList()
    }
})
