package dev.dmigrate.driver.mssql

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionPool
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * Eine Enum-Spalte laesst `schema migrate` gegen echtes SQL Server konvergieren.
 *
 * T-SQL hat keinen Enum-Typ: der Wertevorrat landet als `NVARCHAR` plus
 * benannter CHECK in der Datenbank, und der Reverse liefert ihn als
 * eigenstaendigen Constraint zurueck — das Soll fuehrt ihn am Spaltentyp.
 * Sieht der Vergleich darin zwei verschiedene Dinge, plant der zweite Lauf das
 * Loesen genau des CHECKs, den der erste angelegt hat: die Werte-Durchsetzung
 * verschwindet still, und der Post-Compare meldet Drift.
 *
 * Gefahren wird der ECHTE Runner, nicht der Vergleich allein — nur so haengt
 * der Beleg an dem, was ein Anwender ausfuehrt.
 */
class MssqlEnumMigrateConvergenceIntegrationTest : FunSpec({

    val container = startMssqlContainer()
    lateinit var pool: ConnectionPool

    beforeSpec {
        // Der Runner loest die Typ-Kanonisierung des Ziels ueber die Registry
        // auf und faellt ohne Eintrag still auf die Identitaet zurueck — dann
        // pruefte diese Spec eine Projektion, die im Betrieb eine andere ist.
        DatabaseDriverRegistry.register(MssqlDriver())
        container.start()
        pool = poolFor(container, "dmigrate_enum_convergence")
    }

    afterSpec {
        runCatching { pool.close() }
        container.stop()
    }

    fun schemaWith(values: List<String>) = SchemaDefinition(
        name = "enum_convergence", version = "1",
        tables = mapOf(
            "mood_probe" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                    "mood" to ColumnDefinition(NeutralType.Enum(values = values)),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    /** Ein `schema migrate --execute`-Lauf gegen [want]; liefert Exit-Code und Anweisungszahl. */
    fun migrate(want: SchemaDefinition): Pair<Int, Int> {
        val tmp = createTempDirectory("mssql-enum-convergence")
        try {
            var statementCount = 0
            val errors = mutableListOf<String>()
            val exit = SchemaMigrateRunner(
                fileLoader = { _ -> ResolvedSchemaOperand("desired", want, ValidationResult()) },
                dbLoader = { _, _ -> liveOperand(pool) },
                comparator = { a, b -> SchemaComparator().compare(a, b) },
                targetAwareComparator = { left, right, projection, authorship, serverForm ->
                    SchemaComparator(projection, authorship, serverForm).compare(left, right)
                },
                rendererFor = { d -> if (d == DatabaseDialect.MSSQL) MssqlDiffDdlGenerator() else noRenderer() },
                executor = { _, _, segments, _, _ ->
                    val stmts = segments.flatMap { it.statements }
                    statementCount += stmts.size
                    executeAgainstPool(pool, stmts)
                },
                renderReport = { r, _ -> r.toString() },
                printError = { msg, src -> errors += "[$src] $msg" },
            ).execute(
                SchemaMigrateRequest(
                    source = "file:${tmp.resolve("desired.yaml")}",
                    target = "db:placeholder",
                    dialect = DatabaseDialect.MSSQL,
                    report = tmp.resolve("report.json"),
                    execute = true,
                ),
            )
            withClue(errors.joinToString("; ")) { exit shouldBe 0 }
            return exit to statementCount
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    fun moodConstraints() = readSchema(pool).tables.getValue("mood_probe").constraints

    test("a second migrate against a converged enum column plans nothing and keeps the CHECK") {
        migrate(schemaWith(listOf("red", "green")))
        withClue("der erste Lauf legt die Werte-Durchsetzung an") {
            moodConstraints().map { it.type } shouldBe listOf(ConstraintType.CHECK)
        }

        val (_, statements) = migrate(schemaWith(listOf("red", "green")))

        statements shouldBe 0
        withClue("der zweite Lauf darf den CHECK nicht loesen") {
            moodConstraints().map { it.type } shouldBe listOf(ConstraintType.CHECK)
        }
    }

    test("a changed value vocabulary is still planned and applied") {
        // Die Gegenprobe zur Faltung: der Zieldialekt legt `enum` und
        // Textspalte als denselben Typ ab, der Unterschied steckt allein in
        // den Werten. Bliebe er unsichtbar, migrierte der Lauf ihn nie.
        migrate(schemaWith(listOf("red", "green")))

        val (_, statements) = migrate(schemaWith(listOf("red", "brown")))

        withClue("die Werteliste aendert sich, also gibt es etwas zu tun") { (statements > 0) shouldBe true }
        val check = moodConstraints().single()
        check.type shouldBe ConstraintType.CHECK
        withClue(check.expression.orEmpty()) {
            listOf("brown", "red").all { check.expression.orEmpty().contains("'$it'") } shouldBe true
        }
        withClue("der alte Wert darf nicht stehenbleiben") {
            check.expression.orEmpty().contains("'green'") shouldBe false
        }
    }

    test("a hand-written CHECK that is not a value vocabulary stays a constraint") {
        execDdl(
            pool,
            "CREATE TABLE hand_check (id BIGINT NOT NULL CONSTRAINT pk_hand_check PRIMARY KEY, age INT, " +
                "CONSTRAINT ck_hand_age CHECK (age >= 18))",
        )
        try {
            val reverse = readSchema(pool).tables.getValue("hand_check")
            reverse.constraints.map { it.name } shouldBe listOf("ck_hand_age")
            // Ein Soll ohne diesen CHECK meldet ihn weiterhin als Unterschied —
            // die Faltung greift nur bei einer Werteaufzaehlung.
            val without = SchemaDefinition(
                name = "hand", version = "1",
                tables = mapOf("hand_check" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                        "age" to ColumnDefinition(NeutralType.Integer),
                    ),
                    primaryKey = listOf("id"),
                )),
            )
            val diff = SchemaComparator(
                dev.dmigrate.core.diff.TargetProjection(type = MssqlDriver().typeCanonicalizer()::canonicalize),
            ).compare(SchemaDefinition(name = "hand", version = "1", tables = mapOf("hand_check" to reverse)), without)

            diff.tablesChanged.single().constraintsRemoved.map { it.name } shouldBe listOf("ck_hand_age")
            diff.tablesChanged.single().columnsChanged.shouldBeEmpty()
        } finally {
            execDdl(pool, "DROP TABLE IF EXISTS hand_check")
        }
    }
})
