package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Die drei Uebergaenge einer `AlterColumnGeneration`, die **kein**
 * Ausdruckswechsel sind — auf Oracle, wo zwei der drei Meldungen bisher etwas
 * behaupteten, das nachgemessen nicht stimmt: Oracle **kann** eine virtuelle
 * Spalte zurueckverwandeln (`MODIFY (c <typ>)`) und kann zwei der
 * Identity-Uebergaenge in place (gemessen an 23).
 */
class OracleDiffGenerationTransitionTest : FunSpec({

    val gen = OracleDiffDdlGenerator()
    val planner = DiffPlanner()
    fun empty() = SchemaDefinition(name = "App", version = "1")

    fun renderGenerationChange(
        before: ColumnGeneration?,
        after: ColumnGeneration?,
    ) = gen.generateUp(
        planner.plan(
            empty(),
            empty(),
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "order_line",
                        columnsChanged = listOf(
                            ColumnDiff(name = "line_total", generation = ValueChange(before, after)),
                        ),
                    ),
                ),
            ),
        ),
        DdlGenerationOptions(),
    )

    test("making an ordinary column computed is refused with ORA-54026") {
        val result = renderGenerationChange(null, ColumnGeneration.Computed("\"q\" * \"p\"", stored = false))
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "would make the ordinary column"
        message shouldContain "ORA-54026"
    }

    test("turning a virtual column into an ordinary one names the command Oracle accepts") {
        val result = renderGenerationChange(ColumnGeneration.Computed("\"q\" * \"p\"", stored = false), null)
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "would drop the computed expression"
        message shouldContain "MODIFY"
    }

    test("an identity change is not reported as a computed-expression matter") {
        val result = renderGenerationChange(null, ColumnGeneration.Identity(IdentityMode.BY_DEFAULT))
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "changes the identity of"
        message shouldContain "ORA-30673"
        message shouldNotContain "would drop the computed expression"
    }
})
