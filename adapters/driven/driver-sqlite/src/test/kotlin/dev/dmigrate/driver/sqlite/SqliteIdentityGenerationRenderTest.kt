package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * S2 — der Migrate-Pfad rendert `generation: identity`.
 *
 * Der Voll-Generate-Pfad schrieb `INTEGER PRIMARY KEY AUTOINCREMENT`, der
 * Diff-Pfad sah `generation` gar nicht an: die Spalte entstand als blankes
 * `INTEGER` mit eigener `PRIMARY KEY`-Klausel — gueltiges DDL, angewandt, und
 * das AUTOINCREMENT still verloren (Ursache 1 von
 * `open/sqlite-migrate-biginteger-identity-render-gap.md`). Beide Pfade
 * schreiben jetzt dieselbe Spalte.
 */
class SqliteIdentityGenerationRenderTest : FunSpec({

    val generator = SqliteDdlGenerator()
    val planner = DiffPlanner()
    val diffGen = SqliteDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        diffGen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    fun identityTable(pk: List<String> = listOf("id")) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(
                NeutralType.BigInteger,
                generation = ColumnGeneration.Identity(),
                ordinal = 1,
            ),
            "tenant" to ColumnDefinition(NeutralType.Integer, required = true, ordinal = 2),
            "label" to ColumnDefinition(NeutralType.Text(40), ordinal = 3),
        ),
        primaryKey = pk,
    )

    test("migrate: CreateTable rendert INTEGER PRIMARY KEY AUTOINCREMENT ohne zweite PK-Klausel") {
        val r = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", identityTable()))))
        val createTable = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }

        createTable shouldContain "\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"
        // Eine zweite Klausel gaebe der Tabelle zwei Primaerschluessel; SQLite
        // lehnt sie dann ab.
        createTable shouldNotContain "PRIMARY KEY (\"id\")"
    }

    test("migrate und generate schreiben dieselbe Spalte") {
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to identityTable()))
        val generated = generator.generate(schema).render()
        val migrated = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", identityTable()))))
            .statements.map { it.sql }.first { it.contains("CREATE TABLE") }

        generated shouldContain "\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"
        migrated shouldContain "\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"
    }

    // Gegenprobe 1: in einem zusammengesetzten Schluessel gibt es kein
    // AUTOINCREMENT — die Spalte bleibt ein blankes INTEGER, und der Verlust
    // wird benannt (W135), jetzt auch fuer diese Schreibweise.
    test("migrate: im zusammengesetzten Primaerschluessel bleibt es INTEGER und meldet W135") {
        val r = planAndUp(
            SchemaDiff(tablesAdded = listOf(NamedTable("t", identityTable(pk = listOf("id", "tenant"))))),
        )
        val createTable = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }

        createTable shouldNotContain "AUTOINCREMENT"
        createTable shouldContain "PRIMARY KEY (\"id\", \"tenant\")"
        r.diagnostics.any { it.code == "W135" } shouldBe true
    }

    // Gegenprobe 2: eine gewoehnliche `biginteger`-Spalte ohne `generation`
    // bleibt, was sie war — sonst haenge die Klausel am Typ statt an der
    // erklaerten Erzeugung.
    test("migrate: biginteger ohne generation bekommt kein AUTOINCREMENT") {
        val plain = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.BigInteger, required = true, ordinal = 1),
            ),
            primaryKey = listOf("id"),
        )
        val createTable = planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", plain))))
            .statements.map { it.sql }.first { it.contains("CREATE TABLE") }

        createTable shouldNotContain "AUTOINCREMENT"
        createTable shouldContain "PRIMARY KEY (\"id\")"
    }

    // M2: `ADD COLUMN` legt nie den Primaerschluessel an. Der Aufruf nahm den
    // Default `isSolePrimaryKey = true` und schrieb deshalb fuer **jede**
    // Identity-Spalte ein `PRIMARY KEY AUTOINCREMENT` — SQLite lehnt die
    // Anweisung ab, und erklaert haette sie einen Schluessel, den das Soll
    // nicht nennt.
    test("migrate: ADD COLUMN erklaert keinen Primaerschluessel") {
        val r = planAndUp(
            SchemaDiff(
                tablesChanged = listOf(
                    dev.dmigrate.core.diff.TableDiff(
                        name = "t",
                        columnsAdded = mapOf(
                            "counter" to ColumnDefinition(
                                NeutralType.BigInteger,
                                generation = ColumnGeneration.Identity(),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val addColumn = r.statements.map { it.sql }.first { it.contains("ADD COLUMN") }

        addColumn shouldContain "ADD COLUMN \"counter\" INTEGER"
        addColumn shouldNotContain "PRIMARY KEY"
        addColumn shouldNotContain "AUTOINCREMENT"
    }

    test("migrate: der Tabellen-Neubau behaelt das AUTOINCREMENT") {
        fun schema(labelRequired: Boolean) = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(
                            NeutralType.BigInteger,
                            generation = ColumnGeneration.Identity(),
                            ordinal = 1,
                        ),
                        "label" to ColumnDefinition(NeutralType.Text(40), required = labelRequired, ordinal = 2),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val current = schema(labelRequired = false)
        val desired = schema(labelRequired = true) // Nullbarkeitswechsel → Neubau
        val diff = SchemaComparator().compare(current, desired)
        val r = diffGen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

        val createTable = r.statements.map { it.sql }.first { it.contains("CREATE TABLE") }
        createTable shouldContain "\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"
        createTable shouldNotContain "PRIMARY KEY (\"id\")"
    }
})
