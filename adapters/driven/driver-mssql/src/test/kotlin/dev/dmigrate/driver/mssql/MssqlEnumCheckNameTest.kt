package dev.dmigrate.driver.mssql

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
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.string.shouldNotContain

/**
 * ADR 0069 auf SQL Server: der gesetzte `values_constraint` benennt den CHECK der Inline-Enum, sonst gilt
 * `ck_<tabelle>_<spalte>`. Beim Loesen gilt der Name der Seite, die den CHECK traegt.
 */
class MssqlEnumCheckNameTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

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

    fun change(from: NeutralType, to: NeutralType) = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(name = "t", columnsChanged = listOf(ColumnDiff(name = "mood", type = ValueChange(from, to)))),
        ),
    )

    fun up(current: SchemaDefinition, desired: SchemaDefinition, from: NeutralType, to: NeutralType) =
        gen.generateUp(planner.plan(current, desired, change(from, to)), DdlGenerationOptions()).statements.map { it.sql }

    test("CREATE TABLE: der gesetzte Name ersetzt die Konvention") {
        val ddl = MssqlDdlGenerator().generate(schema(enumOf("ck_mood", "red", "green"))).render()

        ddl shouldContainText "CONSTRAINT [ck_mood] CHECK"
        ddl shouldNotContain "ck_t_mood"
    }

    test("CREATE TABLE ohne Namen: weiter die Konvention") {
        MssqlDdlGenerator().generate(schema(enumOf(null, "red", "green"))).render() shouldContainText "[ck_t_mood]"
    }

    test("ALTER COLUMN: der alte CHECK wird unter seinem gesetzten Namen geloest, der neue unter dem neuen angelegt") {
        val before = enumOf("ck_old", "red", "green")
        val after = enumOf("ck_new", "red", "green", "blue")

        val sqls = up(schema(before), schema(after), before, after)

        sqls.any { it.contains("DROP CONSTRAINT IF EXISTS [ck_old]") } shouldBe true
        sqls.any { it.contains("ADD CONSTRAINT [ck_new] CHECK") } shouldBe true
    }

    test("gegen die Datenbank: der Name der gelesenen CHECK-Zeile wird geloest, nicht die Konvention") {
        val live = schema(
            NeutralType.Text(10),
            listOf(
                ConstraintDefinition("ck_from_db", ConstraintType.CHECK, expression = "[mood]='red' OR [mood]='green'"),
            ),
        )
        val after = enumOf("ck_mood", "red", "green")

        val sqls = up(live, schema(after), NeutralType.Text(10), after)

        sqls.any { it.contains("DROP CONSTRAINT IF EXISTS [ck_from_db]") } shouldBe true
        sqls.any { it.contains("ADD CONSTRAINT [ck_mood] CHECK") } shouldBe true
    }
})
