package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Routine-Migration Slice A pins for the PostgreSQL function
 * renderer. Covers Up + Down for Create, Replace, Drop plus the
 * Down-blocking path when the prior body is unknown.
 */
class PostgresDiffFunctionOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun planAndUp(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun planAndDown(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    val sampleFunction = FunctionDefinition(
        parameters = listOf(ParameterDefinition(name = "amount", type = "numeric")),
        returns = ReturnType(type = "numeric"),
        language = "plpgsql",
        body = "BEGIN\n  RETURN amount * 1.19;\nEND",
    )

    test("CreateFunction (Up) emits CREATE FUNCTION with dollar-quoted body and parameter list") {
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", sampleFunction))))
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE FUNCTION \"compute_total\"")
        statement.shouldContain("(amount numeric)")
        statement.shouldContain("RETURNS numeric")
        statement.shouldContain("LANGUAGE \"plpgsql\"")
        statement.shouldContain("\$body\$")
        statement.shouldContain("RETURN amount * 1.19")
    }

    test("CreateFunction (Up) renders SECURITY DEFINER and search_path when set") {
        val secured = sampleFunction.copy(
            security = RoutineSecurity.DEFINER,
            searchPath = listOf("public", "audit"),
        )
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", secured))))
        val statement = r.statements.single().sql
        statement.shouldContain("SECURITY DEFINER")
        statement.shouldContain("SET search_path = \"public\", \"audit\"")
    }

    test("ReplaceFunction (Up) emits CREATE OR REPLACE with the new body") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN\n  RETURN amount * 1.2;\nEND")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val r = planAndUp(SchemaDiff(functionsChanged = listOf(planner.plan(current, desired, SchemaDiff()).run {
            // Use a real comparator pass to feed the planner; the
            // simpler path is to let the planner do its job via
            // SchemaComparator and SchemaDiff. Below we go via planAndUp.
            dev.dmigrate.core.diff.SchemaComparator().compare(current, desired).functionsChanged.single()
        })), current = current, desired = desired)
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE FUNCTION \"compute_total\"")
        statement.shouldContain("RETURN amount * 1.2")
    }

    test("ReplaceFunction Down with known prior body emits CREATE OR REPLACE reverting to before") {
        val before = sampleFunction
        val after = sampleFunction.copy(body = "BEGIN\n  RETURN amount * 1.2;\nEND")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = dev.dmigrate.core.diff.SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateDown(plan, DdlGenerationOptions())
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE OR REPLACE FUNCTION \"compute_total\"")
        statement.shouldContain("RETURN amount * 1.19") // before body
    }

    test("ReplaceFunction Down without known prior body blocks with ROUTINE_DOWN_BODY_UNKNOWN") {
        // The prior side carries an empty body (e.g. the operator
        // declared the function in the schema file without the
        // body). Down rendering can't reconstruct the old body, so
        // generate-rollback blocks.
        val before = sampleFunction.copy(body = null)
        val after = sampleFunction.copy(body = "BEGIN RETURN amount * 1.2; END")
        val current = emptySchema().copy(functions = mapOf("compute_total" to before))
        val desired = emptySchema().copy(functions = mapOf("compute_total" to after))
        val diff = dev.dmigrate.core.diff.SchemaComparator().compare(current, desired)
        val plan = planner.plan(current, desired, diff)
        val r = gen.generateDown(plan, DdlGenerationOptions())
        r.isBlocked shouldBe true
        r.blockers.single().reason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.any { it.code == "ROUTINE_DOWN_BODY_UNKNOWN" } shouldBe true
        r.statements.shouldBeEmpty()
    }

    test("DropFunction (Up) emits DROP FUNCTION with parameter types") {
        val r = planAndUp(
            SchemaDiff(functionsRemoved = listOf(NamedFunction("compute_total", sampleFunction))),
            current = emptySchema().copy(functions = mapOf("compute_total" to sampleFunction)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("DROP FUNCTION \"compute_total\"(numeric)")
    }

    test("DropFunction Down re-creates the function with the stored definition") {
        val r = planAndDown(
            SchemaDiff(functionsRemoved = listOf(NamedFunction("compute_total", sampleFunction))),
            current = emptySchema().copy(functions = mapOf("compute_total" to sampleFunction)),
        )
        r.isBlocked shouldBe false
        val statement = r.statements.single().sql
        statement.shouldContain("CREATE FUNCTION \"compute_total\"")
        statement.shouldNotContain("OR REPLACE")
    }

    test("CreateFunction without body blocks rather than emitting empty dollar-quotes") {
        // The Up-side has no body — distinct from the Down-side
        // "prior body unknown" case. The diagnostic code is the
        // generic `ROUTINE_BODY_UNKNOWN` (post-review-fix rename).
        val noBody = sampleFunction.copy(body = null)
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("compute_total", noBody))))
        r.isBlocked shouldBe true
        r.diagnostics.shouldNotBeEmpty()
        r.diagnostics.any { it.code == "ROUTINE_BODY_UNKNOWN" } shouldBe true
    }

    test("function body containing the renderer's dollar-tag blocks with ROUTINE_BODY_DOLLAR_TAG_COLLISION") {
        // Review-fix carve-out: hard-coded `$body$` would produce
        // malformed SQL silently if the body literally contained
        // that tag. The renderer detects the collision and blocks.
        val colliding = sampleFunction.copy(body = "BEGIN\n  RETURN \$body\$ || 'x';\nEND")
        val r = planAndUp(SchemaDiff(functionsAdded = listOf(NamedFunction("collider", colliding))))
        r.isBlocked shouldBe true
        r.diagnostics.any { it.code == "ROUTINE_BODY_DOLLAR_TAG_COLLISION" } shouldBe true
    }

    test("DROP FUNCTION drops OUT params and prefixes INOUT (PG signature contract)") {
        val overloaded = sampleFunction.copy(
            parameters = listOf(
                ParameterDefinition(name = "input_val", type = "integer", direction = ParameterDirection.IN),
                ParameterDefinition(name = "io_val", type = "text", direction = ParameterDirection.INOUT),
                ParameterDefinition(name = "out_val", type = "boolean", direction = ParameterDirection.OUT),
            ),
        )
        val r = planAndUp(
            SchemaDiff(functionsRemoved = listOf(NamedFunction("overloaded_fn", overloaded))),
            current = emptySchema().copy(functions = mapOf("overloaded_fn" to overloaded)),
        )
        val statement = r.statements.single().sql
        statement.shouldContain("DROP FUNCTION \"overloaded_fn\"(integer, INOUT text)")
        statement.shouldNotContain("boolean") // OUT param dropped from signature
    }
})
