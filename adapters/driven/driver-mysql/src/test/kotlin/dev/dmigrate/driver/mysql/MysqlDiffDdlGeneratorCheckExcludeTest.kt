package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * F.5 Sub-Slice C (2026-05-19): MySQL CHECK + EXCLUDE rendering with
 * enforcement-capability gate.
 *
 * Split out of [MysqlDiffDdlGeneratorTest] to keep the main file under
 * Detekt's LargeClass threshold.
 */
class MysqlDiffDdlGeneratorCheckExcludeTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MysqlDiffDdlGenerator()
    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(
        diff: SchemaDiff,
        serverVersion: MysqlServerVersion? = null,
    ) = gen.generateUp(
        planner.plan(emptySchema(), emptySchema(), diff),
        DdlGenerationOptions(mysqlServerVersion = serverVersion),
    )

    fun planAndDown(
        diff: SchemaDiff,
        serverVersion: MysqlServerVersion? = null,
    ) = gen.generateDown(
        planner.plan(emptySchema(), emptySchema(), diff),
        DdlGenerationOptions(mysqlServerVersion = serverVersion),
    )

    val checkConstraint = ConstraintDefinition(
        name = "chk_age_nonneg",
        type = ConstraintType.CHECK,
        expression = "age >= 0",
    )
    val excludeConstraint = ConstraintDefinition(
        name = "ex_room_overlap",
        type = ConstraintType.EXCLUDE,
        expression = "room WITH =",
    )
    val addCheckDiff = SchemaDiff(
        tablesChanged = listOf(TableDiff(name = "users", constraintsAdded = listOf(checkConstraint))),
    )
    val dropCheckDiff = SchemaDiff(
        tablesChanged = listOf(TableDiff(name = "users", constraintsRemoved = listOf(checkConstraint))),
    )
    val addExcludeDiff = SchemaDiff(
        tablesChanged = listOf(TableDiff(name = "reservations", constraintsAdded = listOf(excludeConstraint))),
    )

    // ── CHECK ── Add path ────────────────────────────────────────

    test("CHECK ADD on MySQL ≥ 8.0.16: native CHECK clause + DROP CHECK on Down") {
        val up = planAndUp(addCheckDiff, MysqlServerVersion(8, 0, 16)).statements.single().sql
        up shouldContainStr "ADD CONSTRAINT `chk_age_nonneg` CHECK (age >= 0)"
        val down = planAndDown(addCheckDiff, MysqlServerVersion(8, 0, 16)).statements.single().sql
        down shouldContainStr "DROP CHECK `chk_age_nonneg`"
    }

    test("CHECK ADD on MariaDB ≥ 10.2.1: native CHECK clause") {
        val up = planAndUp(addCheckDiff, MysqlServerVersion(10, 2, 1, "MariaDB")).statements.single().sql
        up shouldContainStr "ADD CONSTRAINT `chk_age_nonneg` CHECK (age >= 0)"
    }

    test("CHECK ADD on MySQL < 8.0.16: blocked with MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16") {
        val r = planAndUp(addCheckDiff, MysqlServerVersion(8, 0, 15))
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE
        } shouldBe true
    }

    test("CHECK ADD on MariaDB < 10.2.1: blocked with MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16") {
        val r = planAndUp(addCheckDiff, MysqlServerVersion(10, 2, 0, "MariaDB"))
        r.isBlocked shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE
        } shouldBe true
    }

    test("CHECK ADD without server version: blocked with MYSQL_CHECK_ENFORCEMENT_UNKNOWN") {
        val r = planAndUp(addCheckDiff, serverVersion = null)
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.MANUAL_ACTION_REQUIRED } shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE
        } shouldBe true
    }

    // ── CHECK ── Drop path ───────────────────────────────────────

    test("CHECK DROP on MySQL ≥ 8.0.16: emits DROP CHECK; Down re-adds the constraint") {
        val up = planAndUp(dropCheckDiff, MysqlServerVersion(8, 0, 16)).statements.single().sql
        up shouldContainStr "DROP CHECK `chk_age_nonneg`"
        val down = planAndDown(dropCheckDiff, MysqlServerVersion(8, 0, 16)).statements.single().sql
        down shouldContainStr "ADD CONSTRAINT `chk_age_nonneg` CHECK (age >= 0)"
    }

    test("CHECK DROP on MySQL < 8.0.16 still proceeds (no enforcement needed for drop)") {
        // Pre-8.0.16 MySQL DOES support `ALTER TABLE … DROP CHECK <name>`
        // semantically — the constraint was never enforced anyway — but
        // the syntax landed in 8.0.16. Reality is that pre-8.0.16 the
        // CHECK clause was silently ignored at CREATE time too, so a
        // DROP would target a phantom. We surface this honestly: the
        // capability is `known but !enforced`; logical-drop does NOT
        // require enforcement, so it renders. The operator is on the
        // hook to verify the constraint actually existed.
        val r = planAndUp(dropCheckDiff, MysqlServerVersion(8, 0, 15))
        r.isBlocked shouldBe false
        r.statements.single().sql shouldContainStr "DROP CHECK `chk_age_nonneg`"
    }

    test("CHECK DROP without server version: blocked with MYSQL_CHECK_ENFORCEMENT_UNKNOWN") {
        val r = planAndUp(dropCheckDiff, serverVersion = null)
        r.isBlocked shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE
        } shouldBe true
    }

    // ── EXCLUDE ── unconditional block ───────────────────────────

    test("EXCLUDE ADD on any MySQL: blocked with EXCLUDE_NOT_SUPPORTED_BY_DIALECT") {
        val r = planAndUp(addExcludeDiff, MysqlServerVersion(8, 4, 0))
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION } shouldBe true
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE
        } shouldBe true
    }

    test("EXCLUDE ADD on MariaDB: also blocked (PostgreSQL-only feature)") {
        val r = planAndUp(addExcludeDiff, MysqlServerVersion(10, 11, 6, "MariaDB"))
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE
        } shouldBe true
    }

    test("EXCLUDE ADD without server version: still blocks on the dialect contract (not enforcement)") {
        // EXCLUDE is dialect-unsupported regardless of version — the
        // EXCLUDE branch runs BEFORE the enforcement gate.
        val r = planAndUp(addExcludeDiff, serverVersion = null)
        r.diagnostics.any {
            it.code == PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE
        } shouldBe true
    }

    // ── F.5 Sub-Slice F ─────────────────────────────────────────────

    test("F.5 §F: Replace CHECK on MySQL ≥ 8.0.16 emits DROP CHECK + ADD CONSTRAINT in Up") {
        val before = ConstraintDefinition(
            name = "chk_age_nonneg", type = ConstraintType.CHECK, expression = "age >= 0",
        )
        val after = before.copy(expression = "age >= 18")
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", constraintsChanged = listOf(ValueChange(before, after))),
            ),
        )
        val up = planAndUp(diff, MysqlServerVersion(8, 0, 16)).statements.map { it.sql }
        up.any { it.contains("DROP CHECK `chk_age_nonneg`") } shouldBe true
        up.any { it.contains("ADD CONSTRAINT `chk_age_nonneg` CHECK (age >= 18)") } shouldBe true
    }

    test("F.5 §F: Replace CHECK on MySQL ≥ 8.0.16 — Down emits inverse ADD(old) + DROP(new)") {
        val before = ConstraintDefinition(
            name = "chk_age_nonneg", type = ConstraintType.CHECK, expression = "age >= 0",
        )
        val after = before.copy(expression = "age >= 18")
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(name = "users", constraintsChanged = listOf(ValueChange(before, after))),
            ),
        )
        val down = planAndDown(diff, MysqlServerVersion(8, 0, 16)).statements.map { it.sql }
        down.any { it.contains("ADD CONSTRAINT `chk_age_nonneg` CHECK (age >= 0)") } shouldBe true
        down.any { it.contains("DROP CHECK `chk_age_nonneg`") } shouldBe true
    }

    test("F.5 §F: DropConstraint(CHECK) without expression — DOWN blocks with ROLLBACK_NOT_POSSIBLE") {
        val ghost = ConstraintDefinition(
            name = "chk_ghost", type = ConstraintType.CHECK, expression = null,
        )
        val diff = SchemaDiff(
            tablesChanged = listOf(TableDiff(name = "users", constraintsRemoved = listOf(ghost))),
        )
        val r = planAndDown(diff, MysqlServerVersion(8, 0, 16))
        r.isBlocked shouldBe true
        r.blockers.any { it.reason == MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE } shouldBe true
    }
})
