package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.TargetProjection
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Issue #76 auf Oracle: fuehrt das Soll den Wertevorrat einer Textspalte nur in
 * einem benannten CHECK, gehoert der CHECK einer eigenen Constraint-Operation.
 *
 * Die Spaltenaenderung darf ihn dann nicht zusaetzlich loesen: Oracle kennt
 * kein `DROP CONSTRAINT IF EXISTS`, das zweite `DROP` endete in ORA-02443. Und
 * er darf nicht verschwinden — vor dem Fix loeste der Up ihn und legte keinen
 * neuen an.
 */
class OracleValueCheckOwnershipTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()
    val targetAware = SchemaComparator(TargetProjection(type = { it }))

    fun schema(width: Int, expression: String?) = SchemaDefinition(
        name = "App", version = "1",
        tables = mapOf(
            "users" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "status" to ColumnDefinition(NeutralType.Text(width)),
                ),
                primaryKey = listOf("id"),
                constraints = listOfNotNull(
                    expression?.let { ConstraintDefinition("ck_status", ConstraintType.CHECK, expression = it) },
                ),
            ),
        ),
    )

    fun upAndDown(current: SchemaDefinition, desired: SchemaDefinition) =
        planner.plan(current, desired, targetAware.compare(current, desired)).let { plan ->
            gen.generateUp(plan, DdlGenerationOptions()).statements.map { it.sql } to
                gen.generateDown(plan, DdlGenerationOptions()).statements.map { it.sql }
        }

    test("a changed list is dropped once and added again under its name") {
        val (up, down) = upAndDown(
            schema(10, "\"status\" IN ('a', 'b')"),
            schema(10, "\"status\" IN ('a', 'b', 'c')"),
        )

        up shouldBe listOf(
            "ALTER TABLE \"users\" DROP CONSTRAINT \"ck_status\";",
            "ALTER TABLE \"users\" ADD CONSTRAINT \"ck_status\" CHECK (\"status\" IN ('a', 'b', 'c'));",
        )
        down shouldBe listOf(
            "ALTER TABLE \"users\" DROP CONSTRAINT \"ck_status\";",
            "ALTER TABLE \"users\" ADD CONSTRAINT \"ck_status\" CHECK (\"status\" IN ('a', 'b'));",
        )
    }

    test("with a new length too, the column change does not drop the CHECK a second time") {
        val (up, down) = upAndDown(
            schema(5, "\"status\" IN ('a', 'b')"),
            schema(10, "\"status\" IN ('a', 'b', 'c')"),
        )

        up.count { "DROP CONSTRAINT" in it } shouldBe 1
        up.count { "ADD CONSTRAINT \"ck_status\"" in it } shouldBe 1
        down.count { "DROP CONSTRAINT" in it } shouldBe 1
        down.count { "ADD CONSTRAINT \"ck_status\"" in it } shouldBe 1
    }

    test("the same list in a new length keeps the CHECK and changes only the column") {
        val (up, down) = upAndDown(
            schema(5, "\"status\" IN ('a', 'b')"),
            schema(10, "\"status\" IN ('a', 'b')"),
        )

        up shouldBe listOf("ALTER TABLE \"users\" MODIFY \"status\" VARCHAR2(10);")
        down shouldBe listOf("ALTER TABLE \"users\" MODIFY \"status\" VARCHAR2(5);")
    }
})
