package dev.dmigrate.driver

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * E.1 Routine-Migration Slice D.4: topology-aware pins for the
 * [DependencyGuardEvaluator] body that replaced the Slice-C.3
 * stub heuristic. Two unrelated ops in the same plan are now
 * SAFE — only declared edges flip an op to UNSAFE.
 */
class DependencyGuardEvaluatorTest : FunSpec({

    fun functionOp(id: String, dependencies: Set<String> = emptySet()): DiffOperation.ReplaceFunction =
        DiffOperation.ReplaceFunction(
            id = id,
            objectRef = DiffObjectRef(type = DiffObjectType.FUNCTION, path = listOf(id)),
            before = FunctionDefinition(),
            after = FunctionDefinition(body = "BEGIN RETURN 1; END"),
            dependencies = dependencies,
        )

    fun procedureOp(id: String, dependencies: Set<String> = emptySet()): DiffOperation.ReplaceProcedure =
        DiffOperation.ReplaceProcedure(
            id = id,
            objectRef = DiffObjectRef(type = DiffObjectType.PROCEDURE, path = listOf(id)),
            before = ProcedureDefinition(),
            after = ProcedureDefinition(body = "BEGIN END"),
            dependencies = dependencies,
        )

    fun planOf(vararg ops: DiffOperation): DiffResult = DiffResult(
        current = DiffEndpoint(schemaName = "test"),
        desired = DiffEndpoint(schemaName = "test"),
        schemaDiff = SchemaDiff(),
        operations = ops.toList(),
    )

    test("isolated routine op in the plan yields SAFE") {
        val op = functionOp("f1")
        DependencyGuardEvaluator.evaluate(planOf(op), op) shouldBe DependencyGuard.SAFE
    }

    test("two unrelated ops in the plan are both SAFE (D.4: topology, not stub)") {
        // Slice C.3 stub treated any co-resident op as UNSAFE.
        // The D.4 topology evaluator looks at edges only: with no
        // declared dependency in either direction, both ops are
        // independent → SAFE.
        val a = functionOp("f1")
        val b = functionOp("f2")
        val plan = planOf(a, b)
        DependencyGuardEvaluator.evaluate(plan, a) shouldBe DependencyGuard.SAFE
        DependencyGuardEvaluator.evaluate(plan, b) shouldBe DependencyGuard.SAFE
    }

    test("op with outgoing edge to another op in the plan is UNSAFE") {
        val depTarget = functionOp("target")
        val withEdge = functionOp("dependent", dependencies = setOf("target"))
        val plan = planOf(depTarget, withEdge)
        DependencyGuardEvaluator.evaluate(plan, withEdge) shouldBe DependencyGuard.UNSAFE
    }

    test("op with incoming edge from another op in the plan is UNSAFE") {
        // `dependent` depends on `target` → from `target`'s point of
        // view, `target` has an incoming edge.
        val depTarget = functionOp("target")
        val withEdge = functionOp("dependent", dependencies = setOf("target"))
        val plan = planOf(depTarget, withEdge)
        DependencyGuardEvaluator.evaluate(plan, depTarget) shouldBe DependencyGuard.UNSAFE
    }

    test("outgoing edge pointing outside the plan is ignored") {
        // Dependency IDs that the topological sorter would have
        // already dropped as cross-plan references count as no-ops.
        val op = functionOp("f1", dependencies = setOf("not-in-plan"))
        DependencyGuardEvaluator.evaluate(planOf(op), op) shouldBe DependencyGuard.SAFE
    }

    test("co-resident routines of different kinds without edges are SAFE") {
        val routine = functionOp("f")
        val procedure = procedureOp("p")
        DependencyGuardEvaluator.evaluate(planOf(routine, procedure), routine) shouldBe DependencyGuard.SAFE
    }

    test("DependencyGuard enum exposes exactly the three documented values") {
        DependencyGuard.entries.map { it.name } shouldBe listOf("SAFE", "UNSAFE", "UNKNOWN")
    }
})
