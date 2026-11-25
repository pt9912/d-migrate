package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * ADR 0069 auf Oracle: der gesetzte `values_constraint` benennt den CHECK der Inline-Enum, sonst gilt
 * `ck_<tabelle>_<spalte>`. Oracle vergibt keine Namen selbst; deshalb kennt der Up den Namen des alten CHECKs auch
 * Datei gegen Datei.
 */
class OracleEnumCheckNameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()

    fun enumOf(name: String?, vararg values: String) =
        NeutralType.Enum(values = values.toList(), valuesConstraintName = name)

    fun schema(type: NeutralType, constraints: List<ConstraintDefinition> = emptyList()) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(NeutralType.Integer, required = true), "mood" to ColumnDefinition(type)),
                primaryKey = listOf("id"),
                constraints = constraints,
            ),
        ),
    )

    fun up(current: SchemaDefinition, desired: SchemaDefinition, from: NeutralType, to: NeutralType): List<String> {
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "t", columnsChanged = listOf(ColumnDiff(name = "mood", type = ValueChange(from, to)))),
            ),
        )
        return gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions()).statements.map { it.sql }
    }

    test("CREATE TABLE: der gesetzte Name ersetzt die Konvention") {
        val ddl = OracleDdlGenerator().generate(schema(enumOf("ck_mood", "red", "green"))).render()

        ddl shouldContain "CONSTRAINT \"ck_mood\" CHECK"
        ddl shouldNotContain "ck_t_mood"
    }

    test("CREATE TABLE ohne Namen: weiter die Konvention") {
        OracleDdlGenerator().generate(schema(enumOf(null, "red", "green"))).render() shouldContain "\"ck_t_mood\""
    }

    test("Datei gegen Datei: der alte CHECK wird unter seinem gesetzten Namen geloest, der neue angelegt") {
        val before = enumOf("ck_old", "red", "green")
        val after = enumOf("ck_new", "red", "green", "blue")

        val sqls = up(schema(before), schema(after), before, after)

        sqls.first() shouldBe "ALTER TABLE \"t\" DROP CONSTRAINT \"ck_old\";"
        sqls.any { it.contains("ADD CONSTRAINT \"ck_new\" CHECK") } shouldBe true
    }

    test("Datei gegen Datei ohne Namen: der Up loest den CHECK unter der Konvention, statt einen zweiten anzulegen") {
        val before = enumOf(null, "red", "green")
        val after = enumOf(null, "red", "green", "blue")

        val sqls = up(schema(before), schema(after), before, after)

        sqls.first() shouldBe "ALTER TABLE \"t\" DROP CONSTRAINT \"ck_t_mood\";"
        sqls.any { it.contains("ADD CONSTRAINT \"ck_t_mood\" CHECK") } shouldBe true
    }

    test("gegen die Datenbank: der Name der gelesenen CHECK-Zeile wird geloest, der gesetzte Name angelegt") {
        val live = schema(
            NeutralType.Text(10),
            listOf(ConstraintDefinition("ck_from_db", ConstraintType.CHECK, expression = "\"mood\" IN ('red', 'green')")),
        )
        val after = enumOf("ck_mood", "red", "green")

        val sqls = up(live, schema(after), NeutralType.Text(10), after)

        sqls.first() shouldBe "ALTER TABLE \"t\" DROP CONSTRAINT \"ck_from_db\";"
        sqls.any { it.contains("ADD CONSTRAINT \"ck_mood\" CHECK") } shouldBe true
    }
})
