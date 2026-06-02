package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.NamedTable
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlay
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayKinds
import dev.dmigrate.core.diff.migration.overlay.RenameMappingOverlayEntry
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * Plan-2 §F.4 second slice — dependency-edge & topological-sort
 * guarantees around `RenameTable`. Split out of
 * `RenameOverlayMapperTest` to stay within Detekt's `LargeClass`
 * budget.
 */
class RenameOverlayDependencyTest : FunSpec({

    val planner = DiffPlanner()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun simpleTable() = TableDefinition(
        columns = mapOf(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "email" to ColumnDefinition(type = NeutralType.Text(maxLength = 200)),
        ),
        primaryKey = listOf("id"),
    )

    fun renameOverlay(objectType: String, from: String, to: String): MigrationOverlayDocument {
        val overlay = MigrationOverlay(
            overlayKind = MigrationOverlayKinds.RENAME_MAPPING,
            sourceFingerprint = "src-fp",
            targetFingerprint = "dst-fp",
            dialect = "postgresql",
            entries = listOf(
                RenameMappingOverlayEntry(
                    id = "$from-to-$to",
                    objectType = objectType,
                    fromName = from,
                    toName = to,
                ),
            ),
            createdAt = "2026-05-14T08:00:00Z",
            createdByVersion = "d-migrate-test",
        ).withComputedHash()
        return MigrationOverlayDocument(source = "ovl/rename.json", overlay = overlay)
    }

    test("RenameTable sorts before a CreateTable that FK-references the new name") {
        val parentBefore = simpleTable()
        val parentAfter = simpleTable()
        val orders = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "user_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    references = ReferenceDefinition(table = "users", column = "id"),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val current = emptySchema().copy(tables = mapOf("users_old" to parentBefore))
        val desired = emptySchema().copy(tables = mapOf("users" to parentAfter, "orders" to orders))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", parentAfter), NamedTable("orders", orders)),
            tablesRemoved = listOf(NamedTable("users_old", parentBefore)),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )

        val renameIdx = plan.operations.indexOfFirst { it is DiffOperation.RenameTable }
        val createOrdersIdx = plan.operations.indexOfFirst {
            it is DiffOperation.CreateTable && it.objectRef.rootName == "orders"
        }
        renameIdx shouldBeLessThan createOrdersIdx
        plan.operations.first { it is DiffOperation.CreateTable && it.objectRef.rootName == "orders" }
            .dependencies shouldContain plan.operations[renameIdx].id
    }

    test("RenameTable sorts before an AddConstraint on an existing table referencing the new name") {
        val parentBefore = simpleTable()
        val parentAfter = simpleTable()
        val ordersBefore = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "user_id" to ColumnDefinition(type = NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
        )
        val ordersAfter = ordersBefore.copy(
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_orders_user",
                    type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
                ),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users_old" to parentBefore, "orders" to ordersBefore))
        val desired = emptySchema().copy(tables = mapOf("users" to parentAfter, "orders" to ordersAfter))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", parentAfter)),
            tablesRemoved = listOf(NamedTable("users_old", parentBefore)),
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    constraintsAdded = ordersAfter.constraints,
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )

        val renameIdx = plan.operations.indexOfFirst { it is DiffOperation.RenameTable }
        val addConstraintIdx = plan.operations.indexOfFirst { it is DiffOperation.AddConstraint }
        renameIdx shouldBeLessThan addConstraintIdx
        plan.operations.first { it is DiffOperation.AddConstraint }
            .dependencies shouldContain plan.operations[renameIdx].id
    }

    test("table rename with a forward FK reference is folded and sorted before the new constraint") {
        val parentBefore = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true))),
            primaryKey = listOf("id"),
        )
        val parentAfter = parentBefore
        val ordersBefore = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "user_id" to ColumnDefinition(type = NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_orders_user",
                    type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = ConstraintReferenceDefinition(table = "users_old", columns = listOf("id")),
                ),
            ),
        )
        val ordersAfter = ordersBefore.copy(
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_orders_user",
                    type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("user_id"),
                    references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
                ),
            ),
        )
        val current = emptySchema().copy(tables = mapOf("users_old" to parentBefore, "orders" to ordersBefore))
        val desired = emptySchema().copy(tables = mapOf("users" to parentAfter, "orders" to ordersAfter))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", parentAfter)),
            tablesRemoved = listOf(NamedTable("users_old", parentBefore)),
            tablesChanged = listOf(
                TableDiff(
                    name = "orders",
                    constraintsChanged = listOf(
                        ValueChange(ordersBefore.constraints[0], ordersAfter.constraints[0]),
                    ),
                ),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 1
        val renameIdx = plan.operations.indexOfFirst { it is DiffOperation.RenameTable }
        val addConstraintIdx = plan.operations.indexOfFirst { it is DiffOperation.AddConstraint }
        renameIdx shouldBeLessThan addConstraintIdx
        plan.operations.first { it is DiffOperation.AddConstraint }
            .dependencies shouldContain plan.operations[renameIdx].id
    }

    test("table rename is blocked when a stale FK still references the OLD name in desired state") {
        val parentBefore = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true))),
            primaryKey = listOf("id"),
        )
        val parentAfter = parentBefore
        val ordersBefore = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "user_id" to ColumnDefinition(type = NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
        )
        val staleConstraint = ConstraintDefinition(
            name = "fk_orders_user",
            type = ConstraintType.FOREIGN_KEY,
            columns = listOf("user_id"),
            references = ConstraintReferenceDefinition(table = "users_old", columns = listOf("id")),
        )
        val ordersAfter = ordersBefore.copy(constraints = listOf(staleConstraint))
        val current = emptySchema().copy(tables = mapOf("users_old" to parentBefore, "orders" to ordersBefore))
        val desired = emptySchema().copy(tables = mapOf("users" to parentAfter, "orders" to ordersAfter))
        val diff = SchemaDiff(
            tablesAdded = listOf(NamedTable("users", parentAfter)),
            tablesRemoved = listOf(NamedTable("users_old", parentBefore)),
            tablesChanged = listOf(
                TableDiff(name = "orders", constraintsAdded = listOf(staleConstraint)),
            ),
        )

        val plan = planner.plan(
            current = current,
            desired = desired,
            schemaDiff = diff,
            migrationOverlays = listOf(renameOverlay("table", "users_old", "users")),
        )

        plan.operations.filterIsInstance<DiffOperation.RenameTable>().size shouldBe 0
        plan.operations.filterIsInstance<DiffOperation.CreateTable>().size shouldBe 1
        plan.operations.filterIsInstance<DiffOperation.DropTable>().size shouldBe 1
        plan.diagnostics.map { it.code } shouldContain "RENAME_OVERLAY_DEPENDENCY_PROJECTION_REQUIRED"
    }
})
