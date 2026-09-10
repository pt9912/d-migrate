package dev.dmigrate.driver.oracle

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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.sql.SQLException
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * Eine Spalte, die per ALTER zum Enum wird, traegt ihren Wertevorrat — und der
 * Lauf konvergiert.
 *
 * Oracle hat keinen Enum-Typ: der Vorrat lebt als `VARCHAR2(<laengster Wert>)`
 * plus benanntem CHECK. Der `CreateTable`-Pfad schrieb beides schon, der
 * ALTER-Pfad keines von beidem — er machte die Spalte zu ungebundenem
 * `VARCHAR2(4000)`. Zwei Folgen, beide gemessen: die Werte wurden nicht
 * durchgesetzt, und der Lauf plante dieselbe Aenderung bei JEDEM weiteren
 * Aufruf erneut, weil das Ziel den Vorrat nie trug.
 *
 * Diese Spec faehrt den ECHTEN Runner, damit der Beleg an dem haengt, was ein
 * Anwender ausfuehrt.
 */
class OracleAlterColumnEnumIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))
    lateinit var pool: ConnectionPool

    beforeSpec {
        // Der Runner loest die Typ-Kanonisierung des Ziels ueber die Registry
        // auf und faellt ohne Eintrag still auf die Identitaet zurueck.
        DatabaseDriverRegistry.register(OracleDriver())
        container.start()
        pool = HikariConnectionPoolFactory.create(
            ConnectionConfig(
                dialect = DatabaseDialect.ORACLE,
                host = container.host,
                port = container.oraclePort,
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

    fun schemaWith(moodType: NeutralType) = SchemaDefinition(
        name = "ora_enum", version = "1",
        tables = mapOf("mood_probe" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                "mood" to ColumnDefinition(moodType),
            ),
            primaryKey = listOf("id"),
        )),
    )

    /** Ein `schema migrate --execute`-Lauf; liefert die ausgefuehrten Anweisungen. */
    fun migrate(want: SchemaDefinition): List<String> {
        val tmp = createTempDirectory("ora-enum-alter")
        try {
            val executed = mutableListOf<String>()
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> ResolvedSchemaOperand(
                    reference = "live-oracle",
                    schema = OracleSchemaReader().read(pool).schema,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.ORACLE,
                ) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { l, r, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(l, r)
                },
                rendererFor = { d -> if (d == DatabaseDialect.ORACLE) OracleDiffDdlGenerator() else null },
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
                    dialect = DatabaseDialect.ORACLE,
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

    fun moodColumn() = OracleSchemaReader().read(pool).schema.tables.getValue("mood_probe").columns.getValue("mood")
    fun moodChecks() = OracleSchemaReader().read(pool).schema.tables.getValue("mood_probe").constraints

    fun dropTable() = runCatching {
        pool.borrow().asJdbc().use { c -> c.createStatement().use { it.execute("DROP TABLE \"mood_probe\"") } }
    }

    fun insertMood(value: String): Boolean = runCatching {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { it.execute("INSERT INTO \"mood_probe\" (\"id\", \"mood\") VALUES (1, '$value')") }
        }
    }.also { result ->
        result.exceptionOrNull()?.let { e -> if (e !is SQLException) throw e }
    }.isSuccess

    test("altering a text column to an enum bounds it and adds the CHECK, then converges") {
        dropTable()
        migrate(schemaWith(NeutralType.Text(50)))
        withClue("Vorbedingung: eine gewoehnliche Textspalte ohne Constraint") {
            moodChecks().shouldBeEmpty()
        }

        val statements = migrate(schemaWith(NeutralType.Enum(values = listOf("red", "green"))))

        withClue(statements.toString()) {
            statements.size shouldBe 2
            statements[0] shouldBe "ALTER TABLE \"mood_probe\" MODIFY \"mood\" VARCHAR2(5);"
            statements[1] shouldBe "ALTER TABLE \"mood_probe\" ADD CONSTRAINT \"ck_mood_probe_mood\" " +
                "CHECK (\"mood\" IN ('red', 'green'));"
        }
        // Die Breite haengt am Wertevorrat, nicht am Vorgaenger — sonst bliebe
        // die Spalte auf 50 bzw. dem ungebundenen VARCHAR2(4000) stehen.
        moodColumn().type shouldBe NeutralType.Text(maxLength = 5)
        withClue("ohne diese Zusicherung waere der CHECK nur Text im Katalog") {
            insertMood("blue") shouldBe false
        }

        migrate(schemaWith(NeutralType.Enum(values = listOf("red", "green")))) shouldBe emptyList()
    }

    test("a changed vocabulary replaces the CHECK and converges again") {
        dropTable()
        migrate(schemaWith(NeutralType.Enum(values = listOf("red", "green"))))

        val statements = migrate(schemaWith(NeutralType.Enum(values = listOf("red", "brown"))))

        withClue(statements.toString()) {
            // Der alte CHECK zuerst: Oracle kennt kein `DROP CONSTRAINT IF
            // EXISTS`, und sein Name ist derselbe, den der neue tragen soll.
            statements.first() shouldBe "ALTER TABLE \"mood_probe\" DROP CONSTRAINT \"ck_mood_probe_mood\";"
            statements.any { it.contains("ADD CONSTRAINT") && it.contains("'brown'") } shouldBe true
        }
        withClue(moodChecks().toString()) {
            moodChecks().single().expression.orEmpty().contains("'brown'") shouldBe true
            moodChecks().single().expression.orEmpty().contains("'green'") shouldBe false
        }

        migrate(schemaWith(NeutralType.Enum(values = listOf("red", "brown")))) shouldBe emptyList()
    }

    test("the ALTER path leaves the column exactly as CreateTable would") {
        // Zwei Wege zur selben Spalte muessen dieselbe Spalte ergeben — sonst
        // haengt die Form davon ab, wie die Tabelle entstanden ist.
        dropTable()
        migrate(schemaWith(NeutralType.Text(50)))
        migrate(schemaWith(NeutralType.Enum(values = listOf("red", "green"))))
        val altered = moodColumn().type to moodChecks().map { it.name to it.expression }

        dropTable()
        migrate(schemaWith(NeutralType.Enum(values = listOf("red", "green"))))
        val created = moodColumn().type to moodChecks().map { it.name to it.expression }

        altered shouldBe created
    }
})
