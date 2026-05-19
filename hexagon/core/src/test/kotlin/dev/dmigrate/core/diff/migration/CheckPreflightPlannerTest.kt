package dev.dmigrate.core.diff.migration

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class CheckPreflightPlannerTest : FunSpec({

    fun emptyDiff() = SchemaDiff()

    val current = DiffEndpoint("acme", schemaVersion = "1")
    val desired = DiffEndpoint("acme", schemaVersion = "2")

    fun result(ops: List<DiffOperation>) =
        DiffResult(current, desired, emptyDiff(), operations = ops)

    fun addCheck(
        id: String = "add-chk",
        table: String = "users",
        name: String = "chk_age",
        expression: String? = "age >= 0",
    ) = DiffOperation.AddConstraint(
        id = id,
        objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf(table, name)),
        constraint = ConstraintDefinition(name = name, type = ConstraintType.CHECK, expression = expression),
    )

    val pgQuoter: (String) -> String = { "\"$it\"" }

    test("plans one declaration per AddConstraint(CHECK)") {
        val r = result(listOf(addCheck()))
        val decls = CheckPreflightPlanner.plan(
            r, dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        )
        decls shouldHaveSize 1
        val d = decls.single()
        d.operationId shouldBe "add-chk"
        d.dialect shouldBe "postgresql"
        d.table shouldBe "users"
        d.constraintName shouldBe "chk_age"
        d.expression shouldBe "age >= 0"
        d.initialStatus shouldBe CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET
        d.probeSql shouldContain "SELECT count(*) FROM \"users\" WHERE NOT (age >= 0)"
        d.sqlHash.isNotBlank() shouldBe true
    }

    test("skips DropConstraint(CHECK) — dropping never violates data") {
        val drop = DiffOperation.DropConstraint(
            id = "drop-chk",
            objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("users", "chk_age")),
            constraint = ConstraintDefinition(name = "chk_age", type = ConstraintType.CHECK, expression = "age >= 0"),
        )
        CheckPreflightPlanner.plan(
            result(listOf(drop)), dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        ).shouldBeEmpty()
    }

    test("skips AddConstraint with non-CHECK type (UNIQUE / FOREIGN_KEY / EXCLUDE)") {
        val unique = DiffOperation.AddConstraint(
            id = "u",
            objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("users", "uq_email")),
            constraint = ConstraintDefinition(
                name = "uq_email", type = ConstraintType.UNIQUE, columns = listOf("email"),
            ),
        )
        val fk = DiffOperation.AddConstraint(
            id = "f",
            objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("orders", "fk_orders_users")),
            constraint = ConstraintDefinition(
                name = "fk_orders_users", type = ConstraintType.FOREIGN_KEY,
                columns = listOf("user_id"),
                references = ConstraintReferenceDefinition(table = "users", columns = listOf("id")),
            ),
        )
        val exclude = DiffOperation.AddConstraint(
            id = "e",
            objectRef = DiffObjectRef(DiffObjectType.CONSTRAINT, listOf("reservations", "ex_room")),
            constraint = ConstraintDefinition(
                name = "ex_room", type = ConstraintType.EXCLUDE, expression = "room WITH =",
            ),
        )
        CheckPreflightPlanner.plan(
            result(listOf(unique, fk, exclude)), dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        ).shouldBeEmpty()
    }

    test("skips AddConstraint(CHECK) with blank expression — renderer already routes to DIALECT_UNSUPPORTED_OPERATION") {
        listOf(null, "", "   ").forEach { expr ->
            CheckPreflightPlanner.plan(
                result(listOf(addCheck(expression = expr))), dialect = "postgresql",
                initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
                identifierQuoter = pgQuoter,
            ).shouldBeEmpty()
        }
    }

    test("two AddConstraint(CHECK) ops produce two declarations, deterministically ordered by operation order") {
        val r = result(listOf(
            addCheck(id = "first", table = "a", name = "chk_a"),
            addCheck(id = "second", table = "b", name = "chk_b"),
        ))
        val decls = CheckPreflightPlanner.plan(
            r, dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        )
        decls.map { it.operationId } shouldBe listOf("first", "second")
    }

    test("identifierQuoter is consulted for the probe SQL") {
        val mysqlQuoter: (String) -> String = { "`$it`" }
        val d = CheckPreflightPlanner.plan(
            result(listOf(addCheck(table = "users"))), dialect = "mysql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = mysqlQuoter,
        ).single()
        d.probeSql shouldContain "FROM `users`"
    }

    test("sqlHash is deterministic for identical probeSql and changes when the expression changes") {
        val r1 = CheckPreflightPlanner.plan(
            result(listOf(addCheck(expression = "age >= 0"))), dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        ).single()
        val r2 = CheckPreflightPlanner.plan(
            result(listOf(addCheck(expression = "age >= 0"))), dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        ).single()
        val r3 = CheckPreflightPlanner.plan(
            result(listOf(addCheck(expression = "age >= 18"))), dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET,
            identifierQuoter = pgQuoter,
        ).single()
        r1.sqlHash shouldBe r2.sqlHash
        r1.sqlHash shouldNotBe r3.sqlHash
    }

    test("initialStatus value is carried through unchanged") {
        val withPolicy = CheckPreflightPlanner.plan(
            result(listOf(addCheck())), dialect = "postgresql",
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
            identifierQuoter = pgQuoter,
        ).single()
        withPolicy.initialStatus shouldBe CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY
    }
})
