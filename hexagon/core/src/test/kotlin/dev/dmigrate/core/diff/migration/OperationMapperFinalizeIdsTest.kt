package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.NeutralType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * F.4 dependency-projection T4: pins
 * [OperationMapper.finalizeIds]'s atomic dependency-id remap path.
 *
 * Plan §4.4 point 2 demands a test that **executes the remap branch**:
 * a deliberate ID collision forces `disambiguateOps` to assign a `#N`
 * suffix to one of the colliding ops, and any later op that pinned the
 * original (now-renamed) id in its `dependencies` set must have that
 * reference remapped to the suffixed id — otherwise the synthetic
 * delta would point at a different op (or no op at all).
 *
 * The end-to-end planner tests cannot construct a guaranteed ID
 * collision via overlay inputs because `OperationIdFactory.makeId`
 * hashes the payload — different rename pairs produce different ids by
 * design. This unit test pokes [OperationMapper.finalizeIds] directly.
 */
class OperationMapperFinalizeIdsTest : FunSpec({

    test("colliding op ids and a dependent op are remapped atomically") {
        // Two AddColumn ops on the same table+column with the same
        // payload would normally never appear in a real plan (the
        // mapper produces one per columnsAdded entry), but seeding the
        // disambiguation path is the only way to exercise the remap.
        // We hand-craft three ops sharing one base id:
        //
        //   collidingA : AddColumn id="op-collision"  (no deps)
        //   collidingB : AddColumn id="op-collision"  (no deps) → becomes "op-collision#2"
        //   dependent  : AlterColumnType id="dependent-op" dependencies={ "op-collision" }
        //
        // After finalizeIds, the dependent op's dependency MUST still
        // point at the SECOND op (`op-collision#2`) — i.e. the remap
        // followed the disambiguation, not the original string.
        val collidingA = DiffOperation.AddColumn(
            id = "op-collision",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "a")),
            column = ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
        )
        val collidingB = DiffOperation.AddColumn(
            id = "op-collision",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "b")),
            column = ColumnDefinition(type = NeutralType.Text(maxLength = 100)),
        )
        // The dependent op pins `collidingB`'s base id pre-disambig.
        val dependent = DiffOperation.AlterColumnType(
            id = "dependent-op",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "b")),
            before = NeutralType.Text(maxLength = 100),
            after = NeutralType.Text(maxLength = 500),
            dependencies = setOf("op-collision"),
        )

        val prepared = OperationMapper.PreparedMapping(
            operations = listOf(collidingA, collidingB, dependent),
            diagnostics = emptyList(),
        )
        val result = OperationMapper.finalizeIds(prepared)

        // disambiguate assigns the first op the original id, the
        // second gets the `#2` suffix per OperationIdFactory contract.
        result.operations.map { it.id } shouldContainExactly listOf(
            "op-collision",
            "op-collision#2",
            "dependent-op",
        )

        // The dependent op's dependency reference must have followed
        // the collision-renamed op. Without the remap step, this would
        // still read "op-collision" and silently point at the FIRST
        // op — a dangling-ish reference that contradicts the
        // candidate-id contract.
        val finalDependent = result.operations.filterIsInstance<DiffOperation.AlterColumnType>().single()
        finalDependent.dependencies shouldContain "op-collision#2"
        // Sanity: no stray "op-collision" reference survives.
        finalDependent.dependencies shouldBe setOf("op-collision#2")
    }

    test("non-colliding ops with dependencies are passed through unchanged") {
        // Smoke test the short-circuit: when no ids collide,
        // `finalizeIds` MUST not allocate the remap map or copy any
        // ops via withDependencies.
        val rename = DiffOperation.RenameTable(
            id = "rename-users_old-users",
            objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf("users")),
            fromName = "users_old",
            toName = "users",
            overlaySource = "ovl",
            overlayEntryId = "entry",
            overlayHash = null,
        )
        val addColumn = DiffOperation.AddColumn(
            id = "add-col-users-x",
            objectRef = DiffObjectRef(DiffObjectType.COLUMN, listOf("users", "x")),
            column = ColumnDefinition(type = NeutralType.Text()),
            dependencies = setOf("rename-users_old-users"),
        )
        val prepared = OperationMapper.PreparedMapping(
            operations = listOf(rename, addColumn),
            diagnostics = emptyList(),
        )
        val result = OperationMapper.finalizeIds(prepared)

        result.operations[0].id shouldBe "rename-users_old-users"
        result.operations[1].id shouldBe "add-col-users-x"
        // Reference points at the original id.
        result.operations[1].dependencies shouldBe setOf("rename-users_old-users")
    }

    test("empty PreparedMapping short-circuits to MapperResult with empty ops") {
        val empty = OperationMapper.PreparedMapping(operations = emptyList(), diagnostics = emptyList())
        val result = OperationMapper.finalizeIds(empty)
        result.operations shouldBe emptyList()
        result.diagnostics shouldBe emptyList()
    }
})
