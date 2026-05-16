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
 * E.1 Routine-Migration Slice C.3 pins for the conservative stub
 * heuristic in [DependencyGuardEvaluator]. Slice D will replace the
 * body of [DependencyGuardEvaluator.evaluate] with a real topology
 * evaluator; the SAFE/UNSAFE contract above the stub is what the
 * MySQL renderer reads.
 */
class DependencyGuardEvaluatorTest : FunSpec({

    fun functionOp(id: String): DiffOperation.ReplaceFunction =
        DiffOperation.ReplaceFunction(
            id = id,
            objectRef = DiffObjectRef(type = DiffObjectType.FUNCTION, path = listOf(id)),
            before = FunctionDefinition(),
            after = FunctionDefinition(body = "BEGIN RETURN 1; END"),
        )

    fun procedureOp(id: String): DiffOperation.ReplaceProcedure =
        DiffOperation.ReplaceProcedure(
            id = id,
            objectRef = DiffObjectRef(type = DiffObjectType.PROCEDURE, path = listOf(id)),
            before = ProcedureDefinition(),
            after = ProcedureDefinition(body = "BEGIN END"),
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

    test("two routine ops in the plan yield UNSAFE for each of them") {
        val a = functionOp("f1")
        val b = functionOp("f2")
        val plan = planOf(a, b)
        DependencyGuardEvaluator.evaluate(plan, a) shouldBe DependencyGuard.UNSAFE
        DependencyGuardEvaluator.evaluate(plan, b) shouldBe DependencyGuard.UNSAFE
    }

    test("any co-resident non-routine op also flips SAFE → UNSAFE") {
        // The stub heuristic is intentionally over-conservative —
        // it cannot tell whether a co-resident view/trigger/table
        // op references this routine, so it treats any other op as
        // potential dependency.
        val routine = functionOp("f")
        val procedure = procedureOp("p")
        DependencyGuardEvaluator.evaluate(planOf(routine, procedure), routine) shouldBe DependencyGuard.UNSAFE
    }

    test("DependencyGuard enum exposes exactly the three documented values") {
        DependencyGuard.entries.map { it.name } shouldBe listOf("SAFE", "UNSAFE", "UNKNOWN")
    }
})
