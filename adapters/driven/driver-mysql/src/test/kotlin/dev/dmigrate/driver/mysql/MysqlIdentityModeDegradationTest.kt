package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
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

    // L1: MySQL rendert `AUTO_INCREMENT` nur fuer INT und BIGINT. Auf jedem
    // anderen Typ entsteht gar kein Autowert — eine Meldung „der Modus ist
    // nicht durchgesetzt" zeigte dann auf eine gewoehnliche Spalte. Der
    // Generate-Pfad prueft das seit jeher, der Migrate-Pfad sah nur den Modus.
    test("weder Generate noch Migrate melden W163 fuer einen Typ ohne AUTO_INCREMENT") {
        val decimalIdentity = ColumnDefinition(
            NeutralType.Decimal(18, 0),
            generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
            ordinal = 1,
        )
        val table = TableDefinition(columns = linkedMapOf("id" to decimalIdentity), primaryKey = listOf("id"))
        val schema = SchemaDefinition(name = "s", version = "1", tables = mapOf("t" to table))

        generator.generate(schema).notes.none { it.code == "W163" } shouldBe true
        planAndUp(SchemaDiff(tablesAdded = listOf(NamedTable("t", table))))
            .diagnostics.none { it.code == "W163" } shouldBe true
        planAndUp(
            SchemaDiff(tablesChanged = listOf(TableDiff(name = "t", columnsAdded = mapOf("id" to decimalIdentity)))),
        ).diagnostics.none { it.code == "W163" } shouldBe true
    }

    // M4 (Abdeckungsluecke): der **Wechsel** des Modus nach `always`. Die
    // Stelle rendert `MODIFY COLUMN … AUTO_INCREMENT` und meldete W163,
    // ohne dass ein Test es hielt — die Sabotage blieb gruen.
    test("migrate: der Wechsel des Modus nach always meldet W163") {
        fun schema(generation: ColumnGeneration?) = SchemaDefinition(
            name = "App", version = "1",
            tables = mapOf(
                "t" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.BigInteger, required = true, generation = generation),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )

        fun switchTo(target: ColumnGeneration?, from: ColumnGeneration?) = diffGen.generateUp(
            planner.plan(
                schema(from), schema(target),
                SchemaDiff(
                    tablesChanged = listOf(
                        TableDiff(
                            name = "t",
                            columnsChanged = listOf(ColumnDiff(name = "id", generation = ValueChange(from, target))),
                        ),
                    ),
                ),
            ),
            DdlGenerationOptions(),
        )

        val toAlways = switchTo(ColumnGeneration.Identity(mode = IdentityMode.ALWAYS), null)
        toAlways.statements.single().sql shouldContain "AUTO_INCREMENT"
        toAlways.diagnostics.single { it.code == "W163" }.message shouldContain "mode 'always'"

        // Gegenprobe: der Wechsel nach `by_default` verliert nichts.
        switchTo(ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT), null)
            .diagnostics.none { it.code == "W163" } shouldBe true
        // Und der Wechsel **weg** von der Identity erst recht nicht.
        switchTo(null, ColumnGeneration.Identity(mode = IdentityMode.ALWAYS))
            .diagnostics.none { it.code == "W163" } shouldBe true
    }
})
