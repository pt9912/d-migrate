package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Phase H.4: the 6-entry preflight list on [SqliteRebuildPlan] —
 * declarative readiness data the renderer doesn't consume but
 * report-generators / MCP tooling / JSON-serialisation can inspect.
 *
 * Every plan carries all 6 kinds:
 * `TABLE_EXISTS`, `TEMP_NAME_AVAILABLE`, `SOURCE_COLUMNS_EXIST`,
 * `DEPENDENCIES_KNOWN`, `ADDED_COLUMNS_FILLABLE`,
 * `FOREIGN_KEYS_CHECKABLE`. The per-kind outcome is PASS, FAIL, or
 * INFO per Plan §9 H.4.
 */
class SqliteRebuildH4Test : FunSpec({

    val sql = SqliteDiffSqlBuilders()

    val source = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.SmallInt),
        ),
        primaryKey = listOf("id"),
    )
    val widenedTarget = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(NeutralType.Integer, required = true),
            "age" to ColumnDefinition(NeutralType.Integer),
        ),
        primaryKey = listOf("id"),
    )

    val emptyBucket = emptyList<DiffOperation>()

    fun planFor(
        target: TableDefinition,
        catalog: SqliteCatalogSnapshot = SqliteCatalogSnapshot.EMPTY,
    ): SqliteRebuildPlan = SqliteRebuildPlanner.planRebuild(
        table = "u",
        bucket = emptyBucket,
        source = source,
        target = target,
        bucketRisk = OperationRisk.SAFE,
        sql = sql,
        catalog = catalog,
    )

    test("H.4 — every plan carries exactly 6 preflight entries with disjoint kinds") {
        val plan = planFor(widenedTarget)
        plan.preflight shouldHaveSize 6
        plan.preflight.map { it.kind }.toSet() shouldBe setOf(
            SqliteRebuildPreflightKind.TABLE_EXISTS,
            SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE,
            SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST,
            SqliteRebuildPreflightKind.DEPENDENCIES_KNOWN,
            SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE,
            SqliteRebuildPreflightKind.FOREIGN_KEYS_CHECKABLE,
        )
    }

    test("H.4 — happy-path plan: 4 PASS + 2 INFO, 0 FAIL") {
        val plan = planFor(widenedTarget)
        val byKind = plan.preflight.associateBy { it.kind }

        byKind.getValue(SqliteRebuildPreflightKind.TABLE_EXISTS).outcome shouldBe SqliteRebuildPreflightOutcome.PASS
        byKind.getValue(SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE).outcome shouldBe SqliteRebuildPreflightOutcome.PASS
        byKind.getValue(SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST).outcome shouldBe SqliteRebuildPreflightOutcome.PASS
        byKind.getValue(SqliteRebuildPreflightKind.DEPENDENCIES_KNOWN).outcome shouldBe SqliteRebuildPreflightOutcome.INFO
        byKind.getValue(SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE).outcome shouldBe SqliteRebuildPreflightOutcome.PASS
        byKind.getValue(SqliteRebuildPreflightKind.FOREIGN_KEYS_CHECKABLE).outcome shouldBe SqliteRebuildPreflightOutcome.INFO
    }

    test("H.4 — TEMP_NAME_AVAILABLE flips to INFO when collision-suffix was applied") {
        val base = SqliteRebuildPlanner.tempTableName("u", emptyBucket)
        val catalog = SqliteCatalogSnapshot.EMPTY.copy(tables = setOf(base))
        val plan = planFor(widenedTarget, catalog)
        val tempCheck = plan.preflight.single { it.kind == SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE }
        tempCheck.outcome shouldBe SqliteRebuildPreflightOutcome.INFO
        tempCheck.target shouldBe "${base}__2"
        tempCheck.message.contains("collision-suffix") shouldBe true
    }

    test("H.4 — ADDED_COLUMNS_FILLABLE flips to FAIL when NOT NULL backfill is blocked") {
        val target = source.copy(
            columns = source.columns + (
                "status" to ColumnDefinition(NeutralType.Text(), required = true) // no default → blocker
                ),
        )
        val plan = planFor(target)
        val check = plan.preflight.single { it.kind == SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.FAIL
        check.message.contains("NOT NULL backfill blocked") shouldBe true
        check.message.contains("status") shouldBe true
    }

    test("H.4 — ADDED_COLUMNS_FILLABLE flips to FAIL when cast-matrix blocks") {
        val target = source.copy(
            columns = source.columns + (
                "id" to ColumnDefinition(NeutralType.Text(), required = true)
                ),
        )
        val plan = planFor(target)
        val check = plan.preflight.single { it.kind == SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.FAIL
        check.message.contains("cast-matrix") shouldBe true
    }

    test("H.4 — FOREIGN_KEYS_CHECKABLE is always INFO (runner-vertrag, not statically evaluable)") {
        val plan = planFor(widenedTarget)
        val check = plan.preflight.single { it.kind == SqliteRebuildPreflightKind.FOREIGN_KEYS_CHECKABLE }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.INFO
        check.message.contains("PRAGMA foreign_key_check") shouldBe true
        check.message.contains("runner-vertrag") shouldBe true
    }

    test("H.4 — DEPENDENCIES_KNOWN is INFO (delegated to DiffPlanner F.6.b + G.2)") {
        val plan = planFor(widenedTarget)
        val check = plan.preflight.single { it.kind == SqliteRebuildPreflightKind.DEPENDENCIES_KNOWN }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.INFO
        check.message.contains("F.6.b") shouldBe true
        check.message.contains("G.2") shouldBe true
    }

    test("H.4 — SOURCE_COLUMNS_EXIST passes when preservedColumns.sourceColumn map cleanly") {
        // All preservedColumns are subset of source.columns by
        // construction of computeColumnMapping — the check is mostly
        // a defensive PASS unless the plan was built with a stale
        // catalog. Validate the happy path.
        val plan = planFor(widenedTarget)
        val check = plan.preflight.single { it.kind == SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.PASS
    }

    // ---- Negative paths via direct buildPreflightChecks ----
    // These exercise the defensive FAIL branches that the normal
    // planRebuild API cannot reach (computeColumnMapping enforces
    // preservedColumns ⊆ source.columns by construction). The
    // negative tests pin the contract surface so a future Planner
    // refactor that bypasses computeColumnMapping cannot silently
    // produce a SOURCE_COLUMNS_EXIST=FAIL without flipping the test.

    test("H.4 — SOURCE_COLUMNS_EXIST FAILs when mapping references a column absent from oldTable") {
        val malformedMapping = SqliteColumnMappingModel(
            preservedColumns = listOf(
                // sourceColumn `ghost` is not in `source.columns`.
                ColumnCopyMapping(
                    sourceColumn = "ghost",
                    targetColumn = "ghost",
                    expressionSql = "\"ghost\"",
                    typeChanged = false,
                ),
            ),
            addedColumns = emptyList(),
            droppedColumnNames = emptyList(),
            notNullBackfillBlocked = emptyList(),
            castNotWhitelisted = emptyList(),
        )
        val preflight = SqliteRebuildPlanner.buildPreflightChecks(
            table = "u",
            source = source, // only has `id` + `age`
            mapping = malformedMapping,
            resolvedTempName = "u__dmg_rebuild_xyz",
            catalog = SqliteCatalogSnapshot.EMPTY,
        )
        val check = preflight.single { it.kind == SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.FAIL
        check.message.contains("ghost") shouldBe true
        check.message.contains("not present in oldTable") shouldBe true
    }

    test("H.4 — TEMP_NAME_AVAILABLE FAILs defensively when resolved name still collides") {
        // Resolver bug-simulation: pass a resolved name that the
        // catalog still contains. Plan-time-flag-only — won't happen
        // via normal API (resolveTempTableName iterates until free).
        val preflight = SqliteRebuildPlanner.buildPreflightChecks(
            table = "u",
            source = source,
            mapping = SqliteColumnMappingModel(
                preservedColumns = emptyList(),
                addedColumns = emptyList(),
                droppedColumnNames = emptyList(),
                notNullBackfillBlocked = emptyList(),
                castNotWhitelisted = emptyList(),
            ),
            resolvedTempName = "u__dmg_rebuild_already_taken",
            catalog = SqliteCatalogSnapshot.EMPTY.copy(
                tables = setOf("u__dmg_rebuild_already_taken"),
            ),
        )
        val check = preflight.single { it.kind == SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE }
        check.outcome shouldBe SqliteRebuildPreflightOutcome.FAIL
        check.message.contains("still collides") shouldBe true
        check.message.contains("planner bug") shouldBe true
    }

    test("H.4 — TABLE_EXISTS is structurally PASS (no real negative path in normal API)") {
        // The TABLE_EXISTS check is defensive: planRebuild is only
        // called via SqliteDiffDdlGenerator.renderRebuildBucket, which
        // short-circuits with SQLITE_REBUILD_MISSING_SOURCES if
        // current/desired table is missing — so we never reach
        // buildPreflightChecks for a missing source. Pinned here as a
        // contract: TABLE_EXISTS PASS is unconditional in the current
        // API surface. A future planner-direct call path that bypasses
        // the dispatcher would need to add a negative-path test.
        val preflight = SqliteRebuildPlanner.buildPreflightChecks(
            table = "u",
            source = source,
            mapping = SqliteColumnMappingModel(
                preservedColumns = emptyList(),
                addedColumns = emptyList(),
                droppedColumnNames = emptyList(),
                notNullBackfillBlocked = emptyList(),
                castNotWhitelisted = emptyList(),
            ),
            resolvedTempName = "u__dmg_rebuild_abc",
            catalog = SqliteCatalogSnapshot.EMPTY,
        )
        preflight.single { it.kind == SqliteRebuildPreflightKind.TABLE_EXISTS }.outcome shouldBe
            SqliteRebuildPreflightOutcome.PASS
    }

    test("H.4 — DEPENDENCIES_KNOWN is INFO-only by Plan-Design (F.6.b + G.2 delegated)") {
        // The DEPENDENCIES_KNOWN check is deliberately not plan-time-
        // evaluated in the SQLite adapter — the F.6.b and G.2 blockers
        // live in DiffPlanner (hexagon:core) and block at the
        // application layer before the SQLite renderer is reached. The
        // INFO-entry in this list is purely declarative for downstream
        // tooling (Migrate-Report, MCP). No FAIL path exists at this
        // layer; a future refactor that would shift the dependency
        // check into the SQLite planner would need to add a new
        // outcome path here.
        val preflight = SqliteRebuildPlanner.buildPreflightChecks(
            table = "u",
            source = source,
            mapping = SqliteColumnMappingModel(
                preservedColumns = emptyList(),
                addedColumns = emptyList(),
                droppedColumnNames = emptyList(),
                notNullBackfillBlocked = emptyList(),
                castNotWhitelisted = emptyList(),
            ),
            resolvedTempName = "u__dmg_rebuild_abc",
            catalog = SqliteCatalogSnapshot.EMPTY,
        )
        preflight.single { it.kind == SqliteRebuildPreflightKind.DEPENDENCIES_KNOWN }.outcome shouldBe
            SqliteRebuildPreflightOutcome.INFO
    }

    test("H.4 — FOREIGN_KEYS_CHECKABLE is INFO-only at plan-time (runner-vertrag covers the execute-time check)") {
        // The actual FK-violation check is execute-time and runs in
        // RunnerHookHandler.apply("assert-foreign-keys-clean") — that
        // is where the negative path lives (SQLException on non-empty
        // pragma_foreign_key_check). The plan-time entry is
        // declarative; it exists to make the runner-contract surface
        // visible to inspection tooling. JdbcMigrationExecutorH3bTest
        // pins the actual FAIL behaviour at execute-time.
        val preflight = SqliteRebuildPlanner.buildPreflightChecks(
            table = "u",
            source = source,
            mapping = SqliteColumnMappingModel(
                preservedColumns = emptyList(),
                addedColumns = emptyList(),
                droppedColumnNames = emptyList(),
                notNullBackfillBlocked = emptyList(),
                castNotWhitelisted = emptyList(),
            ),
            resolvedTempName = "u__dmg_rebuild_abc",
            catalog = SqliteCatalogSnapshot.EMPTY,
        )
        preflight.single { it.kind == SqliteRebuildPreflightKind.FOREIGN_KEYS_CHECKABLE }.outcome shouldBe
            SqliteRebuildPreflightOutcome.INFO
    }
})

private infix fun <T> List<T>.shouldHaveSize(size: Int) {
    if (this.size != size) throw AssertionError("Expected size $size but got ${this.size}: $this")
}
