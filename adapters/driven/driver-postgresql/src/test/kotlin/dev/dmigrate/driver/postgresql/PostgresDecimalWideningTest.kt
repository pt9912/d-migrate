package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Issue #79: eine `decimal`-Spalte, die verlustfrei waechst, braucht in PostgreSQL kein
 * `USING`-Overlay. Verlustfrei heisst: weder die Nachkommastellen (`scale`) noch die
 * Vorkommastellen (`precision - scale`) schrumpfen. Jede Verengung bleibt blockiert, denn dort
 * koennte ein Wert gerundet werden oder ueberlaufen.
 */
class PostgresDecimalWideningTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    val empty = SchemaDefinition(name = "App", version = "1")

    fun diff(before: NeutralType, after: NeutralType) = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(
                name = "obj_order",
                columnsChanged = listOf(ColumnDiff(name = "a_amount", type = ValueChange(before, after))),
            ),
        ),
    )

    fun up(before: NeutralType, after: NeutralType) =
        gen.generateUp(planner.plan(empty, empty, diff(before, after)), DdlGenerationOptions())

    fun down(before: NeutralType, after: NeutralType) =
        gen.generateDown(planner.plan(empty, empty, diff(before, after)), DdlGenerationOptions())

    val widening = listOf(
        "mehr Vorkommastellen" to (NeutralType.Decimal(12, 4) to NeutralType.Decimal(14, 4)),
        "mehr Nachkommastellen bei gleichen Vorkommastellen" to (NeutralType.Decimal(12, 4) to NeutralType.Decimal(14, 6)),
        "beides mehr" to (NeutralType.Decimal(12, 4) to NeutralType.Decimal(20, 8)),
    )
    for ((label, types) in widening) {
        val (before, after) = types
        test("verlustfrei ($label): $before -> $after ohne USING") {
            val r = up(before, after)

            withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
            val sql = r.statements.single().sql
            sql shouldContain "ALTER COLUMN \"a_amount\" TYPE DECIMAL(${after.precision},${after.scale})"
            sql shouldNotContain "USING"
        }
    }

    val narrowing = listOf(
        "weniger Vorkommastellen" to (NeutralType.Decimal(12, 4) to NeutralType.Decimal(10, 4)),
        "weniger Nachkommastellen" to (NeutralType.Decimal(12, 4) to NeutralType.Decimal(12, 2)),
        "mehr Nachkommastellen auf Kosten der Vorkommastellen" to (NeutralType.Decimal(12, 4) to NeutralType.Decimal(12, 6)),
    )
    for ((label, types) in narrowing) {
        val (before, after) = types
        test("Verengung ($label): $before -> $after bleibt ohne Overlay blockiert") {
            val r = up(before, after)

            r.isBlocked shouldBe true
            r.diagnostics.map { it.code } shouldContain "PG_USING_OVERLAY_MISSING"
        }
    }

    test("der Rueckweg einer Erweiterung rendert die Verengung wie bei Text ohne USING") {
        val r = down(NeutralType.Decimal(12, 4), NeutralType.Decimal(14, 4))

        withClue(r.diagnostics.toString()) { r.isBlocked shouldBe false }
        val sql = r.statements.single().sql
        sql shouldContain "TYPE DECIMAL(12,4)"
        sql shouldNotContain "USING"
    }
})
