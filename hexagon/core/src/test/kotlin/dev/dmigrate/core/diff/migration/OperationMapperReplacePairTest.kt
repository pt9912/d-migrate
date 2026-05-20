package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith

/**
 * F.5 Sub-Slice F: pins the `replacePairId` propagation through the
 * mapper for `constraintsChanged` (CHECK / EXCLUDE only). The id is
 * deterministic — same inputs in two planning runs produce the same
 * id — and unique enough that two simultaneous Replaces on the same
 * table do not collide.
 */
class OperationMapperReplacePairTest : FunSpec({

    val planner = DiffPlanner()

    fun schema(tables: Map<String, TableDefinition>) = SchemaDefinition(
        name = "App", version = "1", tables = tables,
    )

    fun tableWithCheck(expression: String, name: String = "chk_age") = TableDefinition(
        columns = mapOf("age" to ColumnDefinition(NeutralType.Integer)),
        constraints = listOf(
            ConstraintDefinition(name = name, type = ConstraintType.CHECK, expression = expression),
        ),
    )

    test("changed CHECK constraint emits Drop+Add with shared replacePairId") {
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

        val result = planner.plan(
            current = schema(mapOf("users" to tableWithCheck("age >= 0"))),
            desired = schema(mapOf("users" to tableWithCheck("age >= 18"))),
            schemaDiff = diff,
        )

        val drop = result.operations.filterIsInstance<DiffOperation.DropConstraint>().single()
        val add = result.operations.filterIsInstance<DiffOperation.AddConstraint>().single()
        drop.replacePairId shouldNotBe null
        drop.replacePairId shouldBe add.replacePairId
        drop.replacePairId!! shouldStartWith "replace:"
        // Op ids stay independent — the pair id is a separate identity.
        drop.id shouldNotBe add.id
    }

    test("replacePairId is deterministic across two planning runs with identical inputs") {
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
        fun planOnce() = planner.plan(
            current = schema(mapOf("users" to tableWithCheck("age >= 0"))),
            desired = schema(mapOf("users" to tableWithCheck("age >= 18"))),
            schemaDiff = diff,
        )
        val first = planOnce().operations.filterIsInstance<DiffOperation.DropConstraint>().single()
        val second = planOnce().operations.filterIsInstance<DiffOperation.DropConstraint>().single()
        first.replacePairId shouldBe second.replacePairId
    }

    test("two distinct Replaces on the same table get distinct replacePairIds") {
        val firstBefore = ConstraintDefinition(
            name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0",
        )
        val firstAfter = firstBefore.copy(expression = "age >= 18")
        val secondBefore = ConstraintDefinition(
            name = "chk_name_len", type = ConstraintType.CHECK, expression = "length(name) > 0",
        )
        val secondAfter = secondBefore.copy(expression = "length(name) > 2")
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    constraintsChanged = listOf(
                        ValueChange(firstBefore, firstAfter),
                        ValueChange(secondBefore, secondAfter),
                    ),
                ),
            ),
        )
        val currentTable = TableDefinition(
            columns = mapOf(
                "age" to ColumnDefinition(NeutralType.Integer),
                "name" to ColumnDefinition(NeutralType.Text()),
            ),
            constraints = listOf(firstBefore, secondBefore),
        )
        val desiredTable = currentTable.copy(constraints = listOf(firstAfter, secondAfter))
        val result = planner.plan(
            current = schema(mapOf("users" to currentTable)),
            desired = schema(mapOf("users" to desiredTable)),
            schemaDiff = diff,
        )
        val drops = result.operations.filterIsInstance<DiffOperation.DropConstraint>()
        drops.size shouldBe 2
        drops[0].replacePairId shouldNotBe drops[1].replacePairId
    }

    test("changed EXCLUDE constraint also gets a shared replacePairId") {
        val before = ConstraintDefinition(
            name = "ex_room", type = ConstraintType.EXCLUDE, expression = "room WITH =",
        )
        val after = before.copy(expression = "room_id WITH =")
        val currentTable = TableDefinition(
            columns = mapOf(
                "room" to ColumnDefinition(NeutralType.Integer),
                "room_id" to ColumnDefinition(NeutralType.Integer),
            ),
            constraints = listOf(before),
        )
        val desiredTable = currentTable.copy(constraints = listOf(after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "reservations",
                    constraintsChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val result = planner.plan(
            current = schema(mapOf("reservations" to currentTable)),
            desired = schema(mapOf("reservations" to desiredTable)),
            schemaDiff = diff,
        )
        val drop = result.operations.filterIsInstance<DiffOperation.DropConstraint>().single()
        val add = result.operations.filterIsInstance<DiffOperation.AddConstraint>().single()
        drop.replacePairId shouldNotBe null
        drop.replacePairId shouldBe add.replacePairId
        drop.replacePairId!! shouldStartWith "replace:"
    }

    test("replacePairId does not bleed into the op id — same canonical inputs produce the same op id regardless of pair") {
        // migration-plan.v1 binds artefacts by op id; if `replacePairId`
        // ever leaked into the id derivation, two planning runs with
        // the same inputs but different pair grouping would produce
        // incompatible artefacts. Pin that the id factory ignores the
        // pair field by construction.
        val constraint = ConstraintDefinition(
            name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0",
        )
        val ref = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("users", "chk_age"))
        val expectedId = OperationIdFactory.makeId(
            "AddConstraint",
            ref,
            CanonicalPayload.constraint(constraint),
        )
        val withoutPair = DiffOperation.AddConstraint(
            id = expectedId,
            objectRef = ref,
            constraint = constraint,
        )
        val withPair = withoutPair.copy(replacePairId = "replace:abc123")
        withoutPair.id shouldBe withPair.id
        withoutPair.id shouldBe expectedId
    }

    test("UNIQUE constraint change does NOT carry a replacePairId") {
        val before = ConstraintDefinition(
            name = "u_email", type = ConstraintType.UNIQUE, columns = listOf("email"),
        )
        val after = before.copy(columns = listOf("email_lc"))
        val currentTable = TableDefinition(
            columns = mapOf(
                "email" to ColumnDefinition(NeutralType.Text()),
                "email_lc" to ColumnDefinition(NeutralType.Text()),
            ),
            constraints = listOf(before),
        )
        val desiredTable = currentTable.copy(constraints = listOf(after))
        val diff = SchemaDiff(
            tablesChanged = listOf(
                TableDiff(
                    name = "users",
                    constraintsChanged = listOf(ValueChange(before, after)),
                ),
            ),
        )
        val result = planner.plan(
            current = schema(mapOf("users" to currentTable)),
            desired = schema(mapOf("users" to desiredTable)),
            schemaDiff = diff,
        )
        val drop = result.operations.filterIsInstance<DiffOperation.DropConstraint>().single()
        val add = result.operations.filterIsInstance<DiffOperation.AddConstraint>().single()
        drop.replacePairId shouldBe null
        add.replacePairId shouldBe null
    }
})
