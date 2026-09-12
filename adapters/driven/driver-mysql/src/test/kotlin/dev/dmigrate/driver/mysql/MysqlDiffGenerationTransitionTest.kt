package dev.dmigrate.driver.mysql

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
 * Ausdruckswechsel sind. Sie fielen bisher alle in denselben Zweig und bekamen
 * eine Meldung ueber den Berechnungsausdruck — bei einer Identity-Aenderung
 * stimmte daran kein Wort.
 *
 * Die Aussagen ueber MySQL sind live gegen 9.7.2 gemessen (siehe
 * [dev.dmigrate.driver.ColumnGenerationTransition]).
 */
class MysqlDiffGenerationTransitionTest : FunSpec({

    val gen = MysqlDiffDdlGenerator()
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

    test("making an ordinary column computed is refused with the server's own wording") {
        val result = renderGenerationChange(null, ColumnGeneration.Computed("q * p", stored = false))
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "into a generated column"
        message shouldContain "Changing the STORED status"
    }

    test("turning a computed column back into an ordinary one is refused the same way") {
        val result = renderGenerationChange(ColumnGeneration.Computed("q * p", stored = false), null)
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "back into an ordinary column"
        message shouldContain "Changing the STORED status"
    }

    test("an identity change is not reported as a computed-expression matter") {
        val result = renderGenerationChange(null, ColumnGeneration.Identity(IdentityMode.BY_DEFAULT))
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "changes the identity of"
        message shouldContain "AUTO_INCREMENT"
        message shouldNotContain "would drop the computed expression"
    }
})
