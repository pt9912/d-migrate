package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Enum- und Domain-Spalten im Diff-Pfad.
 *
 * T-SQL kennt keinen Enum-Typ; der Generate-Pfad macht daraus
 * `NVARCHAR(<laengster Wert>)` plus einen benannten CHECK. Beides ist im
 * Diff-Pfad leicht zu verlieren, weil der neutrale Typ es nicht traegt:
 * `MssqlTypeMapper.toSql(Enum)` liefert `NVARCHAR(MAX)`, und der CHECK steht
 * in keiner Modell-Liste. Generate und migrate muessen aber fuer dasselbe
 * Schema dieselbe Tabelle bauen — die Faelle hier halten das fest.
 */
class MssqlDiffEnumColumnTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(vararg tables: Pair<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables.toMap())

    fun up(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    test("the generated enum CHECK is cleared before ALTER COLUMN and restored for the target type") {
        // Er steht in keiner Modell-Liste — er entsteht erst beim Rendern aus
        // dem Spaltentyp. Ohne ihn abzuraeumen ist ALTER COLUMN Msg 5074.
        val before = ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))
        val after = ColumnDefinition(NeutralType.Text(50))
        val current = schema("t" to TableDefinition(columns = linkedMapOf("mood" to before)))
        val desired = schema("t" to TableDefinition(columns = linkedMapOf("mood" to after)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "t",
                    columnsChanged = listOf(
                        ColumnDiff(name = "mood", type = ValueChange(before.type, after.type)),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        val drop = sqls.indexOfFirst { it.contains("DROP CONSTRAINT IF EXISTS [ck_t_mood]") }
        val alter = sqls.indexOfFirst { it.contains("ALTER COLUMN [mood]") }
        (drop in 0 until alter) shouldBe true
        // Das Ziel ist ein blanker Text — es gibt nichts wiederherzustellen.
        sqls.none { it.contains("ADD CONSTRAINT [ck_t_mood]") } shouldBe true
    }
    test("ALTER COLUMN to an enum uses the bounded width, not NVARCHAR(MAX)") {
        // `MssqlTypeMapper.toSql(Enum)` kennt nur den neutralen Typ und liefert
        // NVARCHAR(MAX). Der Generate-Pfad schreibt die begrenzte Breite — und
        // nur die ist schluesselfaehig. Beide Pfade muessen dieselbe Tabelle bauen.
        val before = ColumnDefinition(NeutralType.Text(10))
        val after = ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))
        val current = schema("t" to TableDefinition(columns = linkedMapOf("mood" to before)))
        val desired = schema("t" to TableDefinition(columns = linkedMapOf("mood" to after)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "t",
                    columnsChanged = listOf(
                        ColumnDiff(name = "mood", type = ValueChange(before.type, after.type)),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        sqls.any { it.contains("ALTER COLUMN [mood] NVARCHAR(5)") } shouldBe true
        sqls.none { it.contains("NVARCHAR(MAX)") } shouldBe true
    }
    test("a change BETWEEN enums restores the CHECK with the new values") {
        val before = ColumnDefinition(NeutralType.Enum(values = listOf("red", "green")))
        val after = ColumnDefinition(NeutralType.Enum(values = listOf("red", "green", "blue")))
        val current = schema("t" to TableDefinition(columns = linkedMapOf("mood" to before)))
        val desired = schema("t" to TableDefinition(columns = linkedMapOf("mood" to after)))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "t",
                    columnsChanged = listOf(
                        ColumnDiff(name = "mood", type = ValueChange(before.type, after.type)),
                    ),
                ),
            ),
        )
        val sqls = up(diff, current, desired).statements.map { it.sql }
        val alter = sqls.indexOfFirst { it.contains("ALTER COLUMN [mood]") }
        val readd = sqls.indexOfFirst { it.contains("ADD CONSTRAINT [ck_t_mood]") }
        (readd > alter) shouldBe true
        sqls[readd] shouldContainStr "N'blue'"
    }})
