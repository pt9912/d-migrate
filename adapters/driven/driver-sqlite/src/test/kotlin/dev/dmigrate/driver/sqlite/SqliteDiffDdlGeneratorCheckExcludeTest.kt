package dev.dmigrate.driver.sqlite

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
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * F.5 Sub-Slice D (2026-05-19): SQLite CHECK + EXCLUDE rendering via
 * the rebuild pipeline. CHECK rides through `CREATE TABLE <temp>` —
 * the constraint list is embedded inline. EXCLUDE has no SQLite
 * equivalent and the dispatcher blocks the rebuild before
 * `SqliteRebuildRenderer` is invoked.
 *
 * Split out of [SqliteDiffDdlGeneratorTest] / [SqliteRebuildRendererTest]
 * to keep both files under Detekt's LargeClass threshold and to give
 * the F.5 Sub-Slice D contract a single landing place.
 */
class SqliteDiffDdlGeneratorCheckExcludeTest : FunSpec({

    val planner = DiffPlanner()
    val gen = SqliteDiffDdlGenerator()

    fun schemaWith(tables: Map<String, TableDefinition>) =
        SchemaDefinition(name = "App", version = "1", tables = tables)

    fun render(current: SchemaDefinition, desired: SchemaDefinition, diff: SchemaDiff) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun renderDown(current: SchemaDefinition, desired: SchemaDefinition, diff: SchemaDiff) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    val baseTable = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.Integer),
        ),
        primaryKey = listOf("id"),
    )

    val chkAgeNonneg = ConstraintDefinition(
        name = "chk_age_nonneg",
        type = ConstraintType.CHECK,
        expression = "age >= 0",
    )

    val exRoom = ConstraintDefinition(
        name = "ex_room",
        type = ConstraintType.EXCLUDE,
        expression = "room WITH =",
    )

    // ── CHECK over the rebuild pipeline ──────────────────────────

    test("AddConstraint CHECK triggers rebuild; CREATE temp embeds CHECK clause inline") {
        val after = baseTable.copy(constraints = listOf(chkAgeNonneg))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsAdded = listOf(chkAgeNonneg)),
            ),
        )
        val r = render(schemaWith(mapOf("u" to baseTable)), schemaWith(mapOf("u" to after)), diff)
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldContain "CONSTRAINT \"chk_age_nonneg\" CHECK (age >= 0)"
    }

    test("DropConstraint CHECK triggers rebuild; CREATE temp omits the CHECK clause") {
        val before = baseTable.copy(constraints = listOf(chkAgeNonneg))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsRemoved = listOf(chkAgeNonneg)),
            ),
        )
        val r = render(schemaWith(mapOf("u" to before)), schemaWith(mapOf("u" to baseTable)), diff)
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldNotContain "CHECK"
    }

    test("Replace CHECK (drop + add with same name, new expression) only carries the new expression") {
        val old = chkAgeNonneg
        val updated = chkAgeNonneg.copy(expression = "age >= 18")
        val before = baseTable.copy(constraints = listOf(old))
        val after = baseTable.copy(constraints = listOf(updated))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    constraintsRemoved = listOf(old),
                    constraintsAdded = listOf(updated),
                ),
            ),
        )
        val r = render(schemaWith(mapOf("u" to before)), schemaWith(mapOf("u" to after)), diff)
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldContain "CHECK (age >= 18)"
        createTemp shouldNotContain "CHECK (age >= 0)"
    }

    test("Column reshape on a table with existing CHECK preserves the CHECK in the rebuilt CREATE temp") {
        // No constraint diff op — the CHECK is untouched. A column
        // type-change rebuild must still carry the CHECK forward.
        val before = baseTable.copy(constraints = listOf(chkAgeNonneg))
        val after = baseTable.copy(
            columns = baseTable.columns + ("age" to ColumnDefinition(NeutralType.BigInteger)),
            constraints = listOf(chkAgeNonneg),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        ColumnDiff(name = "age", type = ValueChange(NeutralType.Integer, NeutralType.BigInteger)),
                    ),
                ),
            ),
        )
        val r = render(schemaWith(mapOf("u" to before)), schemaWith(mapOf("u" to after)), diff)
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldContain "CHECK (age >= 0)"
    }

    test("Down direction of AddConstraint CHECK reconstructs the pre-constraint CREATE temp") {
        val after = baseTable.copy(constraints = listOf(chkAgeNonneg))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsAdded = listOf(chkAgeNonneg)),
            ),
        )
        val r = renderDown(schemaWith(mapOf("u" to baseTable)), schemaWith(mapOf("u" to after)), diff)
        // Down rebuild swaps source/target: rebuilt table = original
        // (no CHECK).
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldNotContain "CHECK"
    }

    // ── EXCLUDE blocks the rebuild ───────────────────────────────

    test("AddConstraint EXCLUDE blocks rebuild with EXCLUDE_NOT_SUPPORTED_BY_DIALECT") {
        val after = baseTable.copy(constraints = listOf(exRoom))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsAdded = listOf(exRoom)),
            ),
        )
        val r = render(schemaWith(mapOf("u" to baseTable)), schemaWith(mapOf("u" to after)), diff)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION } shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE
        } shouldBe true
        // No DDL emitted — the dispatcher returns before the rebuild
        // renderer runs.
        r.statements.shouldBeEmpty()
    }

    test("DropConstraint EXCLUDE blocks the rebuild as well") {
        val before = baseTable.copy(constraints = listOf(exRoom))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsRemoved = listOf(exRoom)),
            ),
        )
        val r = render(schemaWith(mapOf("u" to before)), schemaWith(mapOf("u" to baseTable)), diff)
        r.isBlocked shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE
        } shouldBe true
    }

    // ── F.5 Sub-Slice F ──────────────────────────────────────────

    test("F.5 §F: Replace CHECK via constraintsChanged carries the new expression in Up rebuild") {
        val before = chkAgeNonneg
        val after = chkAgeNonneg.copy(expression = "age >= 18")
        val currentTable = baseTable.copy(constraints = listOf(before))
        val desiredTable = baseTable.copy(constraints = listOf(after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    constraintsChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val r = render(
            schemaWith(mapOf("u" to currentTable)),
            schemaWith(mapOf("u" to desiredTable)),
            diff,
        )
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldContain "CHECK (age >= 18)"
        createTemp shouldNotContain "CHECK (age >= 0)"
    }

    test("F.5 §F: Replace CHECK via constraintsChanged — Down rebuild swaps back to the old expression") {
        val before = chkAgeNonneg
        val after = chkAgeNonneg.copy(expression = "age >= 18")
        val currentTable = baseTable.copy(constraints = listOf(before))
        val desiredTable = baseTable.copy(constraints = listOf(after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    constraintsChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val r = renderDown(
            schemaWith(mapOf("u" to currentTable)),
            schemaWith(mapOf("u" to desiredTable)),
            diff,
        )
        r.isBlocked shouldBe false
        val createTemp = r.statements.first { it.sql.contains("CREATE TABLE \"u__dmg_rebuild_") }.sql
        createTemp shouldContain "CHECK (age >= 0)"
        createTemp shouldNotContain "CHECK (age >= 18)"
    }

    test("F.5 §F: DropConstraint(CHECK) without expression — Down rebuild blocks ROLLBACK_NOT_POSSIBLE") {
        val ghost = ConstraintDefinition(
            name = "chk_ghost", type = ConstraintType.CHECK, expression = null,
        )
        val before = baseTable.copy(constraints = listOf(ghost))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "u", constraintsRemoved = listOf(ghost)),
            ),
        )
        val r = renderDown(
            schemaWith(mapOf("u" to before)),
            schemaWith(mapOf("u" to baseTable)),
            diff,
        )
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE } shouldBe true
    }

    test("Column reshape on a table carrying an EXCLUDE blocks even when no op mentions it") {
        // Pre-existing EXCLUDE that the rebuild would otherwise drop
        // silently via constraintLine returning null. The dispatcher
        // must catch the schema-level EXCLUDE and block.
        val before = baseTable.copy(constraints = listOf(exRoom))
        val after = baseTable.copy(
            columns = baseTable.columns + ("age" to ColumnDefinition(NeutralType.BigInteger)),
            constraints = listOf(exRoom),
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "u",
                    columnsChanged = listOf(
                        ColumnDiff(name = "age", type = ValueChange(NeutralType.Integer, NeutralType.BigInteger)),
                    ),
                ),
            ),
        )
        val r = render(schemaWith(mapOf("u" to before)), schemaWith(mapOf("u" to after)), diff)
        r.isBlocked shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE
        } shouldBe true
        r.diagnostics.any {
            it.message.contains("ex_room")
        } shouldBe true
    }
})
