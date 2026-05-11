package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Phase H.1a: pin the [SqliteRebuildPlan] struct shape.
 *
 * H.1a only introduces the data type — no Planner/Renderer wiring yet.
 * These tests pin field defaults and the structured `ColumnCopyMapping`
 * shape so H.1b (data-flow wiring) and later slices (H.2/H.3/H.4
 * populating the deferred fields) can rely on a stable contract.
 */
class SqliteRebuildPlanTest : FunSpec({

    val sampleTable = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(NeutralType.Identifier())),
        primaryKey = listOf("id"),
    )

    fun emptyMapping() = SqliteColumnMappingModel(
        preservedColumns = emptyList(),
        addedColumns = emptyList(),
        droppedColumnNames = emptyList(),
        notNullBackfillBlocked = emptyList(),
        castNotWhitelisted = emptyList(),
    )

    fun minimalPlan() = SqliteRebuildPlan(
        originalTableName = "users",
        oldTable = sampleTable,
        newTable = sampleTable,
        newTableTempName = "users__dmg_rebuild_abcd1234",
        bucketOperations = emptyList(),
        sourceOperationIds = setOf("op-1"),
        risk = OperationRisk.SAFE,
        mapping = emptyMapping(),
        indexesToRecreate = emptyList(),
    )

    test("H.1a — Plan with minimal inputs has all H.3/H.4 fields empty by default") {
        val plan = minimalPlan()
        plan.dependentViewsToDrop.shouldBeEmpty()
        plan.dependentViewsToRecreate.shouldBeEmpty()
        plan.dependentTriggersToDrop.shouldBeEmpty()
        plan.dependentTriggersToRecreate.shouldBeEmpty()
        plan.preflight.shouldBeEmpty()
    }

    test("H.1a — Plan carries DialectMigrationStep contract fields (sourceOperationIds + risk)") {
        // The two §6.4 L880-915 DialectMigrationStep fields must be
        // first-class on the plan — attribution back to absorbed
        // ops + migrate-report risk aggregation depend on them.
        val plan = minimalPlan().copy(
            sourceOperationIds = setOf("alter-col-1", "drop-col-2", "view-replace-3"),
            risk = OperationRisk(destructive = true),
        )
        plan.sourceOperationIds shouldBe setOf("alter-col-1", "drop-col-2", "view-replace-3")
        plan.risk.destructive shouldBe true
    }

    test("H.1a — Mapping isBlocked is true if NOT NULL backfill OR cast not whitelisted") {
        val notNull = emptyMapping().copy(notNullBackfillBlocked = listOf("status"))
        notNull.isBlocked shouldBe true

        val cast = emptyMapping().copy(
            castNotWhitelisted = listOf(
                CastBlockEntry(column = "x", source = NeutralType.Text(), target = NeutralType.Integer),
            ),
        )
        cast.isBlocked shouldBe true

        emptyMapping().isBlocked shouldBe false
    }

    test("H.1a — ColumnCopyMapping carries structured (sourceColumn, targetColumn, expressionSql)") {
        // H.4 SOURCE_COLUMNS_EXIST reads sourceColumn directly; the
        // field must not be hidden inside the SQL expression string.
        val mapping = ColumnCopyMapping(
            sourceColumn = "email",
            targetColumn = "email",
            expressionSql = "CAST(\"email\" AS TEXT)",
            typeChanged = true,
        )
        mapping.sourceColumn shouldBe "email"
        mapping.targetColumn shouldBe "email"
        mapping.typeChanged shouldBe true

        val identity = ColumnCopyMapping(
            sourceColumn = "id",
            targetColumn = "id",
            expressionSql = "\"id\"",
            typeChanged = false,
        )
        identity.typeChanged shouldBe false
    }

    test("H.1a — orderedInsertEntries: preserved first, then added (single insertion order)") {
        val mapping = SqliteColumnMappingModel(
            preservedColumns = listOf(
                ColumnCopyMapping("id", "id", "\"id\"", typeChanged = false),
                ColumnCopyMapping("email", "email", "CAST(\"email\" AS TEXT)", typeChanged = true),
            ),
            addedColumns = listOf(
                AddedColumnFill("status", "'active'"),
                AddedColumnFill("nick", "NULL"),
            ),
            droppedColumnNames = listOf("legacy"),
            notNullBackfillBlocked = emptyList(),
            castNotWhitelisted = emptyList(),
        )
        val entries = mapping.orderedInsertEntries
        entries.map { it.targetColumn } shouldBe listOf("id", "email", "status", "nick")
        entries.map { it.expressionSql } shouldBe listOf(
            "\"id\"", "CAST(\"email\" AS TEXT)", "'active'", "NULL",
        )
    }

    test("H.1a — six preflight kinds are exposed in the enum") {
        // Pin the §6.4 L928-934 contract: exactly six kinds.
        SqliteRebuildPreflightKind.values().toSet() shouldBe setOf(
            SqliteRebuildPreflightKind.TABLE_EXISTS,
            SqliteRebuildPreflightKind.TEMP_NAME_AVAILABLE,
            SqliteRebuildPreflightKind.SOURCE_COLUMNS_EXIST,
            SqliteRebuildPreflightKind.DEPENDENCIES_KNOWN,
            SqliteRebuildPreflightKind.ADDED_COLUMNS_FILLABLE,
            SqliteRebuildPreflightKind.FOREIGN_KEYS_CHECKABLE,
        )
    }

    test("H.1a — dependentViewsToDrop and dependentViewsToRecreate are separate lists") {
        // Two divergent sets per §6.4 / Plan H.3 — view removed in the
        // same plan is in drop-list only; view added in the same plan
        // is in recreate-list only; view replaced in place is in both.
        val plan = minimalPlan().copy(
            dependentViewsToDrop = listOf(
                NamedViewDefinition(
                    "v_legacy",
                    dev.dmigrate.core.model.ViewDefinition(query = "SELECT id FROM users"),
                ),
            ),
            dependentViewsToRecreate = listOf(
                NamedViewDefinition(
                    "v_new",
                    dev.dmigrate.core.model.ViewDefinition(query = "SELECT id, email FROM users"),
                ),
            ),
        )
        plan.dependentViewsToDrop.single().name shouldBe "v_legacy"
        plan.dependentViewsToRecreate.single().name shouldBe "v_new"
        // The two lists do not overlap by name — divergence is the
        // whole point of having them separate.
        val dropNames = plan.dependentViewsToDrop.map { it.name }.toSet()
        val recreateNames = plan.dependentViewsToRecreate.map { it.name }.toSet()
        (dropNames intersect recreateNames).shouldBeEmpty()
    }

    test("H.1a — Plan can carry indices + preflight for H.3/H.4 to populate") {
        val plan = minimalPlan().copy(
            indexesToRecreate = listOf(
                IndexDefinition(
                    name = "idx_users_id",
                    columns = listOf(IndexColumn("id")),
                    type = IndexType.BTREE,
                ),
            ),
            preflight = listOf(
                SqliteRebuildPreflightCheck(
                    kind = SqliteRebuildPreflightKind.TABLE_EXISTS,
                    target = "users",
                    message = "expected table 'users' exists in current schema",
                ),
            ),
        )
        plan.indexesToRecreate shouldBe listOf(
            IndexDefinition(
                name = "idx_users_id",
                columns = listOf(IndexColumn("id")),
                type = IndexType.BTREE,
            ),
        )
        plan.preflight.single().kind shouldBe SqliteRebuildPreflightKind.TABLE_EXISTS
        plan.preflight.single().target shouldBe "users"
    }
})
