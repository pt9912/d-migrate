package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Der Rueckweg einer Aenderung am Wertevorrat einer Inline-Enum-Spalte (Issue #61).
 *
 * Der Up loest den alten CHECK und legt den neuen **unbenannt** an. Der Down muss den vom Up
 * angelegten CHECK loesen und den alten wieder anlegen — in dieser Reihenfolge. Kennt er den Namen
 * des neuen CHECKs nicht sicher, blockiert er, statt einen `DROP` zu raten.
 */
class PostgresEnumCheckRollbackTest : FunSpec({

    val planner = DiffPlanner()
    val gen = PostgresDiffDdlGenerator()

    fun check(name: String, vararg values: String) = ConstraintDefinition(
        name = name,
        type = ConstraintType.CHECK,
        // Die Form, in der der Reader den Katalog zurueckgibt.
        expression = "(e = ANY (ARRAY[" + values.joinToString(", ") { "'$it'::text" } + "]))",
    )

    fun schema(
        column: NeutralType,
        constraints: List<ConstraintDefinition> = emptyList(),
        table: String = "t",
    ) = SchemaDefinition(
        name = "s", version = "1",
        tables = mapOf(
            table to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "e" to ColumnDefinition(column, required = true),
                ),
                primaryKey = listOf("id"),
                constraints = constraints,
            ),
        ),
    )

    fun enumOf(vararg v: String) = NeutralType.Enum(values = v.toList())

    fun typeChange(from: NeutralType, to: NeutralType, table: String = "t") = SchemaDiff(
        tablesChanged = listOf(
            TableDiff(name = table, columnsChanged = listOf(ColumnDiff(name = "e", type = ValueChange(from, to)))),
        ),
    )

    fun sqls(r: dev.dmigrate.driver.migration.MigrationDdlResult) = r.statements.map { it.sql }

    test("widened list: the CHECK of the Up is dropped, the old one comes back under its name") {
        val live = schema(NeutralType.Text(), listOf(check("t_e_check", "a", "b")))
        val file = schema(enumOf("a", "b", "c"))
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b", "c"))),
            DdlGenerationOptions(),
        )
        sqls(r) shouldBe listOf(
            "ALTER TABLE \"t\" DROP CONSTRAINT \"t_e_check\";",
            "ALTER TABLE \"t\" ADD CONSTRAINT \"t_e_check\" CHECK (\"e\" IN ('a', 'b'));",
        )
        r.isBlocked shouldBe false
    }

    test("shortened list: the old, longer list comes back") {
        val live = schema(NeutralType.Text(), listOf(check("t_e_check", "a", "b", "c")))
        val file = schema(enumOf("a", "b"))
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b"))),
            DdlGenerationOptions(),
        )
        sqls(r) shouldBe listOf(
            "ALTER TABLE \"t\" DROP CONSTRAINT \"t_e_check\";",
            "ALTER TABLE \"t\" ADD CONSTRAINT \"t_e_check\" CHECK (\"e\" IN ('a', 'b', 'c'));",
        )
    }

    test("a user-named old CHECK is restored under its name; the Up's CHECK carries the default name") {
        val live = schema(NeutralType.Text(), listOf(check("my_e_values", "a", "b")))
        val file = schema(enumOf("a", "b", "c"))
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b", "c"))),
            DdlGenerationOptions(),
        )
        sqls(r) shouldBe listOf(
            "ALTER TABLE \"t\" DROP CONSTRAINT \"t_e_check\";",
            "ALTER TABLE \"t\" ADD CONSTRAINT \"my_e_values\" CHECK (\"e\" IN ('a', 'b'));",
        )
    }

    test("a text column that gains a value list drops the CHECK the Up added, restoring nothing") {
        val live = schema(NeutralType.Text())
        val file = schema(enumOf("a", "b"))
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b"))),
            DdlGenerationOptions(),
        )
        sqls(r) shouldBe listOf("ALTER TABLE \"t\" DROP CONSTRAINT \"t_e_check\";")
    }

    test("an enum column that loses its value list gets the dropped CHECK back") {
        val live = schema(NeutralType.Text(), listOf(check("t_e_check", "a", "b")))
        val file = schema(NeutralType.Text())
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), NeutralType.Text())),
            DdlGenerationOptions(),
        )
        sqls(r) shouldBe listOf(
            "ALTER TABLE \"t\" ADD CONSTRAINT \"t_e_check\" CHECK (\"e\" IN ('a', 'b'));",
        )
    }

    test("the default name is taken by another constraint: the Down blocks instead of guessing") {
        val live = schema(
            NeutralType.Text(),
            listOf(
                check("my_e_values", "a", "b"),
                ConstraintDefinition(name = "t_e_check", type = ConstraintType.CHECK, expression = "id > 0"),
            ),
        )
        val file = schema(enumOf("a", "b", "c"))
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b", "c"))),
            DdlGenerationOptions(),
        )
        r.isBlocked shouldBe true
        r.blockers.map { it.reason } shouldContain MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
        r.diagnostics.map { it.code } shouldContain "POSTGRES_ENUM_CHECK_ROLLBACK_NAME_UNKNOWN"
        r.statements.size shouldBe 0
    }

    test("a name that PostgreSQL would truncate is not guessed") {
        val longTable = "t".repeat(60)
        val live = schema(NeutralType.Text(), listOf(check("whatever", "a", "b")), longTable)
        val file = schema(enumOf("a", "b", "c"), table = longTable)
        val r = gen.generateDown(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b", "c"), longTable)),
            DdlGenerationOptions(),
        )
        r.blockers.map { it.reason } shouldContain MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("file-to-file: the old side has no catalog name, so the Down cannot name the CHECK of the Up") {
        // Beide Seiten tragen die Werte inline, es gibt keinen Constraint mit Namen.
        val current = schema(enumOf("a", "b"))
        val desired = schema(enumOf("a", "b", "c"))
        val r = gen.generateDown(
            planner.plan(current, desired, typeChange(enumOf("a", "b"), enumOf("a", "b", "c"))),
            DdlGenerationOptions(),
        )
        r.isBlocked shouldBe true
        r.blockers.map { it.reason } shouldContain MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("the Up is unchanged: it drops the old CHECK by its catalog name and adds an unnamed one") {
        val live = schema(NeutralType.Text(), listOf(check("my_e_values", "a", "b")))
        val file = schema(enumOf("a", "b", "c"))
        val r = gen.generateUp(
            planner.plan(live, file, typeChange(NeutralType.Text(), enumOf("a", "b", "c"))),
            DdlGenerationOptions(),
        )
        sqls(r) shouldBe listOf(
            "ALTER TABLE \"t\" DROP CONSTRAINT IF EXISTS \"my_e_values\";",
            "ALTER TABLE \"t\" ADD CHECK (\"e\" IN ('a', 'b', 'c'));",
        )
    }
})
