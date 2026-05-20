package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTable
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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * F.5 Sub-Slice B (2026-05-19): PostgreSQL CHECK + EXCLUDE rendering.
 *
 * Split out of [PostgresDiffDdlGeneratorTest] to keep that file under
 * Detekt's `LargeClass` threshold while pinning each CHECK / EXCLUDE
 * shape independently.
 */
class PostgresDiffDdlGeneratorCheckExcludeTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(diff: SchemaDiff) =
        gen.generateUp(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    fun planAndDown(diff: SchemaDiff) =
        gen.generateDown(planner.plan(emptySchema(), emptySchema(), diff), DdlGenerationOptions())

    test("AddConstraint CHECK renders ADD CONSTRAINT … CHECK (expr); up + down") {
        val c = ConstraintDefinition(
            name = "chk_age_nonneg",
            type = ConstraintType.CHECK,
            expression = "age >= 0",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(c))),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "ADD CONSTRAINT \"chk_age_nonneg\" CHECK (age >= 0)"
        val down = planAndDown(diff).statements.single().sql
        down shouldContainStr "DROP CONSTRAINT \"chk_age_nonneg\""
    }

    test("DropConstraint CHECK round-trip up + down") {
        val c = ConstraintDefinition(
            name = "chk_age_nonneg",
            type = ConstraintType.CHECK,
            expression = "age >= 0",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", constraintsRemoved = listOf(c))),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "DROP CONSTRAINT \"chk_age_nonneg\""
        val down = planAndDown(diff).statements.single().sql
        down shouldContainStr "ADD CONSTRAINT \"chk_age_nonneg\" CHECK (age >= 0)"
    }

    test("CreateTable inline emits CHECK constraint in the body") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "age" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0"),
            ),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("orders", table)))
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "CONSTRAINT \"chk_age\" CHECK (age >= 0)"
    }

    test("AddConstraint EXCLUDE renders EXCLUDE USING gist (…); up + down") {
        val c = ConstraintDefinition(
            name = "ex_room_overlap",
            type = ConstraintType.EXCLUDE,
            expression = "room WITH =, during WITH &&",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "reservations", constraintsAdded = listOf(c))),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "ADD CONSTRAINT \"ex_room_overlap\" EXCLUDE USING gist (room WITH =, during WITH &&)"
        val down = planAndDown(diff).statements.single().sql
        down shouldContainStr "DROP CONSTRAINT \"ex_room_overlap\""
    }

    test("DropConstraint EXCLUDE round-trip up + down") {
        val c = ConstraintDefinition(
            name = "ex_room_overlap",
            type = ConstraintType.EXCLUDE,
            expression = "room WITH =, during WITH &&",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "reservations", constraintsRemoved = listOf(c))),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "DROP CONSTRAINT \"ex_room_overlap\""
        val down = planAndDown(diff).statements.single().sql
        down shouldContainStr "ADD CONSTRAINT \"ex_room_overlap\" EXCLUDE USING gist (room WITH =, during WITH &&)"
    }

    test("CreateTable inline emits EXCLUDE USING gist constraint in the body") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "room" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ex_room",
                    type = ConstraintType.EXCLUDE,
                    expression = "room WITH =",
                ),
            ),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("reservations", table)))
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "CONSTRAINT \"ex_room\" EXCLUDE USING gist (room WITH =)"
    }

    test("blank CHECK expression falls back to DIALECT_UNSUPPORTED_OPERATION blocker") {
        // The renderer treats a blank expression as not-renderable —
        // constraintLine returns null and the standard skip/blocker
        // path emits DIALECT_UNSUPPORTED_OPERATION, mirroring how an
        // unimplemented constraint type would be handled.
        val c = ConstraintDefinition(
            name = "chk_empty",
            type = ConstraintType.CHECK,
            expression = "",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(c))),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION } shouldBe true
    }

    test("blank EXCLUDE expression falls back to DIALECT_UNSUPPORTED_OPERATION blocker") {
        val c = ConstraintDefinition(
            name = "ex_empty",
            type = ConstraintType.EXCLUDE,
            expression = "",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "reservations", constraintsAdded = listOf(c))),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION } shouldBe true
    }

    // ── F.5 Sub-Slice F ─────────────────────────────────────────────

    test("F.5 §F: Replace CHECK up emits DROP+ADD; down emits ADD(old)+DROP(new) inverse") {
        val before = ConstraintDefinition(
            name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0",
        )
        val after = before.copy(expression = "age >= 18")
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    constraintsChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val up = planAndUp(diff).statements.map { it.sql }
        // Up: drop the old then add the new.
        up.any { it.contains("DROP CONSTRAINT \"chk_age\"") } shouldBe true
        up.any { it.contains("ADD CONSTRAINT \"chk_age\" CHECK (age >= 18)") } shouldBe true
        val down = planAndDown(diff).statements.map { it.sql }
        // Down: inverse — re-add the old, drop the new.
        down.any { it.contains("ADD CONSTRAINT \"chk_age\" CHECK (age >= 0)") } shouldBe true
        down.any { it.contains("DROP CONSTRAINT \"chk_age\"") } shouldBe true
    }

    test("F.5 §F: DropConstraint(CHECK) without expression — DOWN blocks with ROLLBACK_NOT_POSSIBLE") {
        // The stored constraint metadata has no expression payload —
        // the renderer cannot reconstruct the inverse ADD CONSTRAINT.
        val c = ConstraintDefinition(
            name = "chk_ghost", type = ConstraintType.CHECK, expression = null,
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", constraintsRemoved = listOf(c))),
        )
        val downReport = planAndDown(diff)
        downReport.isBlocked shouldBe true
        downReport.blockers.any { it.reason == MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE } shouldBe true
        // The specific diagnostic code is surfaced (not the generic
        // NOT_REVERSIBLE short-circuit message) so the report can
        // explain WHY the inverse failed.
        downReport.diagnostics.map { it.code } shouldContain
            "CONSTRAINT_ROLLBACK_EXPRESSION_MISSING"
    }

    test("F.5 §F: AddConstraint(EXCLUDE) with custom opclass blocks with EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED") {
        val c = ConstraintDefinition(
            name = "ex_room",
            type = ConstraintType.EXCLUDE,
            // `room my_int4_ops WITH =` carries an explicit operator
            // class token between the column and WITH — outside the
            // F.5 whitelist.
            expression = "room my_int4_ops WITH =",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "reservations", constraintsAdded = listOf(c))),
        )
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.diagnostics.map { it.code } shouldContain
            PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
    }

    test("F.5 §F: AddConstraint(EXCLUDE) with bare-column standard form still renders successfully") {
        val c = ConstraintDefinition(
            name = "ex_ok",
            type = ConstraintType.EXCLUDE,
            expression = "room WITH =, during WITH &&",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "reservations", constraintsAdded = listOf(c))),
        )
        val up = planAndUp(diff).statements.single().sql
        up shouldContainStr "ADD CONSTRAINT \"ex_ok\" EXCLUDE USING gist (room WITH =, during WITH &&)"
    }

    test("F.5 §F: inline CreateTable with custom-opclass EXCLUDE is blocked") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "room" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ex_room",
                    type = ConstraintType.EXCLUDE,
                    expression = "room my_opclass WITH =",
                ),
            ),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("reservations", table)))
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        r.diagnostics.map { it.code } shouldContain
            PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE
    }

    test("F.5 §F: DropConstraint(EXCLUDE) with custom opclass — DOWN blocks (inverse ADD would be bad too)") {
        // Drop direction is just `DROP CONSTRAINT name` — fine. But
        // Down emits ADD CONSTRAINT … EXCLUDE USING gist (...), which
        // would need to re-emit the unsupported opclass expression.
        // The gate fires before the reconstruction.
        val c = ConstraintDefinition(
            name = "ex_bad",
            type = ConstraintType.EXCLUDE,
            expression = "room my_opclass WITH =",
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "reservations", constraintsRemoved = listOf(c))),
        )
        val r = planAndDown(diff)
        r.isBlocked shouldBe true
        r.diagnostics.map { it.code } shouldContain
            PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE
    }

    test("F.5 §F: DOWN of DropConstraint(FOREIGN_KEY) without references falls back to DIALECT_UNSUPPORTED_OPERATION") {
        // Non-CHECK/EXCLUDE constraint without enough metadata to
        // render the inverse — `constraintLine` returns null and the
        // dispatcher routes to the dialect-unsupported branch rather
        // than ROLLBACK_NOT_POSSIBLE (FK rollback is a different
        // failure mode: the catalog cannot render the inverse, not the
        // expression).
        val fkWithoutRef = ConstraintDefinition(
            name = "fk_orders_users",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            // references intentionally null
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "orders", constraintsRemoved = listOf(fkWithoutRef))),
        )
        val r = planAndDown(diff)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION } shouldBe true
    }

    test("F.5 §F: inline CreateTable with multiple EXCLUDEs, one bad — blocks on the offender") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Identifier(), required = true),
                "room" to ColumnDefinition(NeutralType.Integer),
                "during" to ColumnDefinition(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ex_good", type = ConstraintType.EXCLUDE,
                    expression = "room WITH =",
                ),
                ConstraintDefinition(
                    name = "ex_bad", type = ConstraintType.EXCLUDE,
                    expression = "during my_opclass WITH &&",
                ),
            ),
        )
        val diff = SchemaDiff(tablesAdded = listOf(NamedTable("reservations", table)))
        val r = planAndUp(diff)
        r.isBlocked shouldBe true
        // Block message names the offender.
        r.diagnostics.any { it.message.contains("ex_bad") } shouldBe true
    }
})
