package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.ColumnDiff
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
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * `column-generation-diff-unmapped.md`: der Kind-Wechsel (gewoehnlich ↔
 * berechnet) auf SQL Server. Bis hierher blockte `renderTableOp`s generischer
 * Fallback alle vier `AlterColumnGeneration`-Uebergaenge gleich, weil T-SQL
 * keinen In-Place-Zweig kennt (`ALTER COLUMN … AS (…)` ist ein Syntaxfehler).
 * Kind-Wechsel laufen jetzt ueber den Spaltentausch; Modus-Wechsel und reiner
 * Ausdruckswechsel bleiben geblockt (siehe `MssqlDiffDdlGeneratorTest`).
 */
class MssqlDiffGenerationOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    fun schema(generation: ColumnGeneration?) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "qty" to ColumnDefinition(NeutralType.Integer, required = true),
                    "total" to ColumnDefinition(NeutralType.Integer, generation = generation),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun renderGenerationChange(before: ColumnGeneration?, after: ColumnGeneration?) = gen.generateUp(
        planner.plan(
            schema(before),
            schema(after),
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "order_line",
                        columnsChanged = listOf(ColumnDiff(name = "total", generation = ValueChange(before, after))),
                    ),
                ),
            ),
        ),
        DdlGenerationOptions(),
    )

    val computed = ColumnGeneration.Computed("qty * 2", stored = true)

    test("an ordinary column becomes computed via DROP + ADD, no data copy") {
        // `MssqlDiffTableOps.dropColumnStatements` (reused for the DROP step)
        // prepends its generic "drop the auto-named default constraint if one
        // exists" dance ahead of every `DROP COLUMN` -- a runtime-checked
        // no-op here (the column carries no default), but the same statement
        // any other DropColumn render already produces.
        val result = renderGenerationChange(null, computed)
        val sqls = result.statements.map { it.sql }
        sqls shouldHaveSize 3
        sqls[0] shouldContain "DROP CONSTRAINT"
        sqls[1] shouldBe "ALTER TABLE [order_line] DROP COLUMN [total];"
        sqls[2] shouldBe "ALTER TABLE [order_line] ADD [total] AS (qty * 2) PERSISTED;"
        result.destructiveOperations shouldHaveSize 1
        result.requiresConfirmation shouldBe true
    }

    test("a computed column becomes ordinary via swap: add temp, copy, drop, rename, reassert") {
        // Two default-constraint dances: one ahead of `dropColumnStatements`
        // for the old computed column, one ahead of the final
        // `alterColumnWithDefaultDance` reassertion -- both runtime no-ops
        // here, both the same statement every other MSSQL column-alter path
        // already emits.
        val result = renderGenerationChange(computed, null)
        val sqls = result.statements.map { it.sql }
        sqls shouldHaveSize 7
        sqls[0] shouldBe "ALTER TABLE [order_line] ADD [total__dmg_swap] INT;"
        sqls[1] shouldBe "UPDATE [order_line] SET [total__dmg_swap] = [total];"
        sqls[2] shouldContain "DROP CONSTRAINT"
        sqls[3] shouldBe "ALTER TABLE [order_line] DROP COLUMN [total];"
        sqls[4] shouldBe "EXEC sp_rename 'order_line.total__dmg_swap', 'total', 'COLUMN';"
        sqls[5] shouldContain "DROP CONSTRAINT"
        sqls[6] shouldBe "ALTER TABLE [order_line] ALTER COLUMN [total] INT NULL;"
        result.destructiveOperations shouldHaveSize 1
    }

    test("an encumbered column (part of the primary key) blocks the swap with a precise reason") {
        fun schemaWithIdGeneration(generation: ColumnGeneration?) = SchemaDefinition(
            name = "App",
            version = "1",
            tables = mapOf(
                "order_line" to TableDefinition(
                    columns = linkedMapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true, generation = generation),
                        "qty" to ColumnDefinition(NeutralType.Integer, required = true),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val result = gen.generateUp(
            planner.plan(
                schemaWithIdGeneration(null),
                schemaWithIdGeneration(computed),
                SchemaDiff(
                    tablesChanged = listOf(
                        TableDiff(
                            name = "order_line",
                            columnsChanged = listOf(ColumnDiff(name = "id", generation = ValueChange(null, computed))),
                        ),
                    ),
                ),
            ),
            DdlGenerationOptions(),
        )
        result.statements.shouldBeEmpty()
        result.diagnostics.single().message shouldContain "part of the primary key"
    }

    test("switching the identity mode transition is still blocked with the identity-specific message") {
        val result = renderGenerationChange(null, ColumnGeneration.Identity(IdentityMode.BY_DEFAULT))
        result.statements.shouldBeEmpty()
        result.primaryBlockedReason shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        val message = result.diagnostics.single().message
        message shouldContain "identity"
        message shouldContain "IDENTITY"
    }
})
