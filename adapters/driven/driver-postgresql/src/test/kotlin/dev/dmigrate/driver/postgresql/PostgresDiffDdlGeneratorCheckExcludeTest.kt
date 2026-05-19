package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
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
})
