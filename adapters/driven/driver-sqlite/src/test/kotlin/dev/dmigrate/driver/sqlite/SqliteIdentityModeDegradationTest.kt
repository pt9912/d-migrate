package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * P10: SQLite kennt keinen Identity-Modus. Eine Spalte mit `mode: always`
 * entsteht dort als `INTEGER PRIMARY KEY AUTOINCREMENT`, das einen
 * ausdruecklich gesetzten Wert annimmt (am Server gemessen) — die Zusage ist
 * nicht durchgesetzt. `W163` sagt das; `by_default` und der Typ `identifier`
 * melden nichts.
 *
 * Auf dem Migrate-Pfad entstand die Meldung erst, seit er die Spalte
 * ueberhaupt als Autowert rendert (S2).
 */
class SqliteIdentityModeDegradationTest : FunSpec({

    val generator = SqliteDdlGenerator()
    val planner = DiffPlanner()
    val diffGen = SqliteDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        diffGen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    fun tableWith(mode: IdentityMode, pk: List<String> = listOf("id")) = TableDefinition(
        columns = linkedMapOf(
            "id" to ColumnDefinition(
                NeutralType.BigInteger,
                generation = ColumnGeneration.Identity(mode = mode),
                ordinal = 1,
            ),
            "tenant" to ColumnDefinition(NeutralType.Integer, required = true, ordinal = 2),
        ),
        primaryKey = pk,
    )

    test("generate: always meldet W163 und rendert weiter AUTOINCREMENT") {
        val schema = SchemaDefinition(
            name = "s", version = "1", tables = mapOf("t" to tableWith(IdentityMode.ALWAYS)),
        )
        val result = generator.generate(schema)

        result.render() shouldContain "AUTOINCREMENT"
        val note = result.notes.single { it.code == "W163" }
        note.objectName shouldBe "t.id"
        note.message shouldContain "mode 'always'"
    }

    test("generate: by_default meldet W163 nicht") {
        val schema = SchemaDefinition(
            name = "s", version = "1", tables = mapOf("t" to tableWith(IdentityMode.BY_DEFAULT)),
        )
        generator.generate(schema).notes.none { it.code == "W163" } shouldBe true
    }

    // Im zusammengesetzten Schluessel entsteht gar kein Autowert: dort sagt
    // W135 das Staerkere, und W163 waere daneben irrefuehrend.
    test("generate: im zusammengesetzten Schluessel meldet W135, nicht W163") {
        val schema = SchemaDefinition(
            name = "s", version = "1",
            tables = mapOf("t" to tableWith(IdentityMode.ALWAYS, pk = listOf("id", "tenant"))),
        )
        val result = generator.generate(schema)
        result.notes.any { it.code == "W135" } shouldBe true
        result.notes.none { it.code == "W163" } shouldBe true
    }

    test("migrate: CreateTable meldet W163 nur bei always") {
        planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", tableWith(IdentityMode.ALWAYS)))))
            .diagnostics.single { it.code == "W163" }.message shouldContain "mode 'always'"

        planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", tableWith(IdentityMode.BY_DEFAULT)))))
            .diagnostics.none { it.code == "W163" } shouldBe true
    }

    test("migrate: im zusammengesetzten Schluessel meldet W135, nicht W163") {
        val r = planAndUp(
            SchemaDiff(tablesAdded = listOf(NamedTable("t", tableWith(IdentityMode.ALWAYS, listOf("id", "tenant"))))),
        )
        r.diagnostics.any { it.code == "W135" } shouldBe true
        r.diagnostics.none { it.code == "W163" } shouldBe true
    }
})
