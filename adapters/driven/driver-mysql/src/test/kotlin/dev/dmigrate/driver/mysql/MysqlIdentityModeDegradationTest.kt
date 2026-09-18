package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
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
 * P10: MySQL kennt keinen Identity-Modus. Eine Spalte mit `mode: always`
 * entsteht dort als `AUTO_INCREMENT`, das einen ausdruecklich gesetzten Wert
 * annimmt — die Zusage ist nicht durchgesetzt, und ein Reverse liest
 * `by_default`. `W163` sagt das; `by_default` und der Typ `identifier`
 * melden nichts.
 */
class MysqlIdentityModeDegradationTest : FunSpec({

    val generator = MysqlDdlGenerator()
    val planner = DiffPlanner()
    val diffGen = MysqlDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")
    fun planAndUp(diff: SchemaDiff) =
        diffGen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    fun identityColumn(mode: IdentityMode) = ColumnDefinition(
        NeutralType.BigInteger,
        generation = ColumnGeneration.Identity(mode = mode),
        ordinal = 1,
    )

    fun tableWith(mode: IdentityMode) = TableDefinition(
        columns = linkedMapOf(
            "id" to identityColumn(mode),
            "label" to ColumnDefinition(NeutralType.Text(40), ordinal = 2),
        ),
        primaryKey = listOf("id"),
    )

    test("generate: always meldet W163 und rendert weiter AUTO_INCREMENT") {
        val schema = SchemaDefinition(
            name = "s", version = "1", tables = mapOf("t" to tableWith(IdentityMode.ALWAYS)),
        )
        val result = generator.generate(schema)

        result.render() shouldContain "AUTO_INCREMENT"
        val note = result.notes.single { it.code == "W163" }
        note.objectName shouldBe "t.id"
        note.message shouldContain "mode 'always'"
        note.message shouldContain "by_default"
    }

    // Gegenprobe 1: `by_default` verliert nichts.
    test("generate: by_default meldet W163 nicht") {
        val schema = SchemaDefinition(
            name = "s", version = "1", tables = mapOf("t" to tableWith(IdentityMode.BY_DEFAULT)),
        )
        generator.generate(schema).notes.none { it.code == "W163" } shouldBe true
    }

    // Gegenprobe 2: der Typ `identifier` nennt gar keinen Modus — es gibt
    // nichts zu verlieren und nichts zu melden.
    test("generate: identifier meldet W163 nicht") {
        val table = TableDefinition(
            columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Identifier(true), ordinal = 1)),
            primaryKey = listOf("id"),
        )
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to table))
        generator.generate(schema).notes.none { it.code == "W163" } shouldBe true
    }

    test("migrate: CreateTable meldet W163 nur bei always") {
        planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", tableWith(IdentityMode.ALWAYS)))))
            .diagnostics.single { it.code == "W163" }.message shouldContain "mode 'always'"

        planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", tableWith(IdentityMode.BY_DEFAULT)))))
            .diagnostics.none { it.code == "W163" } shouldBe true
    }

    test("migrate: ADD COLUMN meldet W163 nur bei always") {
        fun addColumn(mode: IdentityMode) = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "t", columnsAdded = mapOf("id" to identityColumn(mode)))),
        )
        planAndUp(addColumn(IdentityMode.ALWAYS)).diagnostics.any { it.code == "W163" } shouldBe true
        planAndUp(addColumn(IdentityMode.BY_DEFAULT)).diagnostics.none { it.code == "W163" } shouldBe true
    }
})
