package dev.dmigrate.driver.oracle

import dev.dmigrate.cli.commands.ResolvedSchemaOperand
import dev.dmigrate.cli.commands.SchemaMigrateRequest
import dev.dmigrate.cli.commands.SchemaMigrateRunner
import dev.dmigrate.cli.commands.capabilityGenerationCanonicalizer
import dev.dmigrate.cli.commands.testing.executeAgainstPool
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.TargetProjection
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.DiffDdlGenerator
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.testcontainers.oracle.OracleContainer
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory

/**
 * Der Tabellen-Neubau, mit dem `schema migrate` eine bestehende Spalte in
 * Oracle doch noch zur Identity-Spalte macht — gegen ein echtes Oracle.
 *
 * Der Neubau ist der einzige Weg: `ALTER TABLE t MODIFY <col> GENERATED
 * ALWAYS AS IDENTITY` antwortet auf jeder Spalte, die nicht bereits Identity
 * traegt, mit `ORA-30673`. Er loescht und legt neu an — was ihn zu genau der
 * Art Operation macht, die man nicht gegen Unit-Tests allein abnimmt. Belegt
 * werden deshalb die Zusicherungen, die ein Anwender daran knuepft:
 *
 * - die **vorhandenen Schluessel** ueberleben unveraendert,
 * - der **Zaehler** steht danach ueber dem groessten davon (der naechste
 *   Insert kollidiert nicht),
 * - die **Nutzdaten** der uebrigen Spalten stehen noch da,
 * - **Index, Constraint und eingehender Fremdschluessel** sind wieder da —
 *   `DROP TABLE … CASCADE CONSTRAINTS` raeumt letzteren mit ab.
 */
class OracleIdentityRebuildIntegrationTest : FunSpec({

    val container = OracleContainer("gvenzl/oracle-free:23-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5))

    lateinit var pool: ConnectionPool

    beforeSpec {
        // Der Post-Compare des Runners holt seine Typ-Projektion aus der
        // Registry; ohne Eintrag faellt er still auf die Identitaet zurueck
        // und vergliche zwei Schemata durch verschiedene Brillen.
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

    fun execDdl(vararg sqls: String) {
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt -> sqls.forEach { stmt.execute(it) } }
        }
    }

    fun <T> query(sql: String, read: (java.sql.ResultSet) -> T): List<T> =
        pool.borrow().asJdbc().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(read(rs)) }
                }
            }
        }

    fun readSchema(): SchemaDefinition = OracleSchemaReader().read(pool).schema

    fun liveOperand(): ResolvedSchemaOperand = ResolvedSchemaOperand(
        reference = "live-oracle",
        schema = readSchema(),
        validation = ValidationResult(),
        dialect = DatabaseDialect.ORACLE,
    )

    /** Dieselbe Projektion, die der Migrate-Pfad fuer Oracle waehlt. */
    fun projection() = TargetProjection(
        type = { type -> OracleDriver().typeCanonicalizer().canonicalize(type, emptyMap()) },
        generation = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE),
        foldsAutoIncrementOntoIdentity =
            DialectCapabilities.forDialect(DatabaseDialect.ORACLE).rendersAutoIncrementAsIdentity,
    )

    fun noRenderer(): DiffDdlGenerator = error("test wires only the Oracle renderer")

    fun runMigrate(desired: SchemaDefinition, tmp: Path, errors: MutableList<String>): Int =
        SchemaMigrateRunner(
            fileLoader = { _ ->
                ResolvedSchemaOperand(reference = "desired", schema = desired, validation = ValidationResult())
            },
            dbLoader = { _, _ -> liveOperand() },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            targetAwareComparator = { left, right, canonicalize, _, _ -> SchemaComparator(canonicalize).compare(left, right) },
            rendererFor = { d -> if (d == DatabaseDialect.ORACLE) OracleDiffDdlGenerator() else noRenderer() },
            executor = { _, _, segments, _, _ -> executeAgainstPool(pool, segments.flatMap { it.statements }) },
            renderReport = { r, _ -> r.toString().also { errors += it } },
            printError = { msg, src -> errors += "[$src] $msg" },
        ).execute(
            SchemaMigrateRequest(
                source = "file:${tmp.resolve("ignored-desired.yaml")}",
                target = "db:placeholder",
                dialect = DatabaseDialect.ORACLE,
                report = tmp.resolve("report.json"),
                execute = true,
                allowDestructive = true,
            ),
        )

    test("a plain key column becomes an identity column and takes its data, keys and neighbours along") {
        val tmp = createTempDirectory("oracle-identity-rebuild")
        val errors = mutableListOf<String>()
        try {
            execDdl(
                """CREATE TABLE "reb_user" (
                     "id" NUMBER(18) NOT NULL,
                     "email" VARCHAR2(200) NOT NULL,
                     "city" VARCHAR2(80),
                     CONSTRAINT "pk_reb_user" PRIMARY KEY ("id"),
                     CONSTRAINT "uq_reb_user_email" UNIQUE ("email")
                   )""",
                """CREATE INDEX "ix_reb_user_city" ON "reb_user" ("city")""",
                """CREATE TABLE "reb_order" (
                     "id" NUMBER(18) NOT NULL,
                     "user_id" NUMBER(18) NOT NULL,
                     CONSTRAINT "pk_reb_order" PRIMARY KEY ("id"),
                     CONSTRAINT "fk_reb_order_user" FOREIGN KEY ("user_id")
                       REFERENCES "reb_user" ("id")
                   )""",
                """INSERT INTO "reb_user" ("id", "email", "city") VALUES (7, 'seven@example.com', 'Bonn')""",
                """INSERT INTO "reb_user" ("id", "email", "city") VALUES (42, 'answer@example.com', 'Kiel')""",
                """INSERT INTO "reb_order" ("id", "user_id") VALUES (1, 42)""",
            )

            val live = readSchema()
            // Das Soll ist der gelesene Ist-Zustand mit EINER Aenderung: die
            // Schluesselspalte in der von Hand geschriebenen Autowert-Form.
            // Genau dieser Uebergang ist der, den Oracle nicht per ALTER kann.
            val liveUser = live.tables.getValue("reb_user")
            val desired = SchemaDefinition(
                name = "rebuild-desired", version = "1",
                tables = live.tables + mapOf(
                    "reb_user" to liveUser.copy(
                        columns = LinkedHashMap(liveUser.columns).apply {
                            put(
                                "id",
                                getValue("id").copy(
                                    type = NeutralType.Identifier(autoIncrement = true),
                                    generation = null,
                                ),
                            )
                        },
                    ),
                ),
            )

            // Vorbedingung: es gibt genau die eine Aenderung.
            val planned = SchemaComparator(projection()).compare(live, desired)
            withClue("geplant: ${planned.tablesChanged}") {
                planned.tablesChanged.map { it.name } shouldContainExactly listOf("reb_user")
                planned.tablesChanged.single().columnsChanged.map { it.name } shouldContainExactly listOf("id")
            }

            val migrateExit = runMigrate(desired, tmp, errors)
            withClue(
                errors.joinToString("\n") + "\nRestdiff: " +
                    SchemaComparator(projection()).compare(readSchema(), desired).tablesChanged,
            ) { migrateExit shouldBe 0 }

            // Die Schluessel sind noch da -- und noch dieselben.
            query("""SELECT "id" FROM "reb_user" ORDER BY "id"""") { it.getLong(1) } shouldContainExactly
                listOf(7L, 42L)
            // ... samt Nutzdaten.
            query("""SELECT "email" FROM "reb_user" ORDER BY "id"""") { it.getString(1) } shouldContainExactly
                listOf("seven@example.com", "answer@example.com")
            query("""SELECT "city" FROM "reb_user" ORDER BY "id"""") { it.getString(1) } shouldContainExactly
                listOf("Bonn", "Kiel")

            // Der Zaehler steht ueber dem groessten kopierten Wert: der
            // naechste Insert kollidiert nicht mit 42.
            execDdl("""INSERT INTO "reb_user" ("email", "city") VALUES ('next@example.com', 'Ulm')""")
            query("""SELECT MAX("id") FROM "reb_user"""") { it.getLong(1) }.single() shouldBe 43L

            // Die Spalte ist wirklich eine Identity-Spalte.
            query(
                """SELECT "IDENTITY_COLUMN" FROM USER_TAB_COLUMNS
                   WHERE TABLE_NAME = 'reb_user' AND COLUMN_NAME = 'id'""",
            ) { it.getString(1) }.single() shouldBe "YES"

            // Index und Constraint kamen zurueck.
            query(
                """SELECT INDEX_NAME FROM USER_INDEXES WHERE TABLE_NAME = 'reb_user' ORDER BY INDEX_NAME""",
            ) { it.getString(1) } shouldContainExactly
                listOf("ix_reb_user_city", "pk_reb_user", "uq_reb_user_email")
            query(
                """SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS
                   WHERE TABLE_NAME = 'reb_user' AND CONSTRAINT_TYPE IN ('P', 'U')
                   ORDER BY CONSTRAINT_NAME""",
            ) { it.getString(1) } shouldContainExactly listOf("pk_reb_user", "uq_reb_user_email")

            // Der eingehende Fremdschluessel ebenfalls -- er faellt beim
            // DROP TABLE ... CASCADE CONSTRAINTS mit weg.
            query(
                """SELECT CONSTRAINT_NAME FROM USER_CONSTRAINTS
                   WHERE TABLE_NAME = 'reb_order' AND CONSTRAINT_TYPE = 'R'""",
            ) { it.getString(1) } shouldContainExactly listOf("fk_reb_order_user")
            // ... und er greift: ein Kind ohne Elternteil geht nicht durch.
            runCatching {
                execDdl("""INSERT INTO "reb_order" ("id", "user_id") VALUES (2, 999)""")
            }.isFailure shouldBe true
        } finally {
            runCatching { execDdl("""DROP TABLE "reb_order" CASCADE CONSTRAINTS PURGE""") }
            runCatching { execDdl("""DROP TABLE "reb_user" CASCADE CONSTRAINTS PURGE""") }
            tmp.toFile().deleteRecursively()
        }
    }
})
