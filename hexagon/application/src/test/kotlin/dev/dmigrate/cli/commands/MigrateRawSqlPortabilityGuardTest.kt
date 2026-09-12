package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Rohen Ausdruckstext, den das Ziel nicht parsen kann, faengt der
 * Migrationspfad **vor** dem Anwenden ab.
 *
 * Der generate-Pfad laesst ihn weg und meldet; hier wird geblockt, weil
 * `--execute` die Anweisung sofort an den Server schickt — der lehnt sie ab,
 * aber erst mitten in einer Folge bereits angewandter Anweisungen.
 */
class MigrateRawSqlPortabilityGuardTest : FunSpec({

    /** Steht fuer `RawSqlExpressionPortability`: `::` gilt ausserhalb PostgreSQL nicht. */
    val assess: RawSqlPortabilityFn = { text, dialect ->
        if (dialect != DatabaseDialect.POSTGRESQL && text?.contains("::") == true) "PostgreSQL-style cast (::)" else null
    }

    fun ref(vararg path: String) = DiffObjectRef(DiffObjectType.COLUMN, path.toList())

    fun planOf(vararg ops: DiffOperation) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = ops.toList(),
    )

    fun rendered(vararg opIds: String) = MigrationDdlResult(
        statements = emptyList(),
        operationsRendered = opIds.toSet(),
    )

    val foreignComputed = ColumnGeneration.Computed("(quantity)::numeric * unit_price", stored = true)

    fun addColumn(id: String, column: ColumnDefinition) = DiffOperation.AddColumn(
        id = id,
        objectRef = ref("order_items", "line_total"),
        column = column,
    )

    test("a foreign computed expression on a rendered operation blocks the run") {
        val op = addColumn("op-1", ColumnDefinition(NeutralType.Decimal(14, 2), generation = foreignComputed))
        val plan = planOf(op)

        val guarded = MigrateRawSqlPortabilityGuard.apply(
            rendered("op-1"), plan, DatabaseDialect.MSSQL, assess,
        )

        guarded.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        guarded.blockers.single().operationIds shouldBe setOf("op-1")
        val diagnostic = guarded.diagnostics.single()
        diagnostic.code shouldBe "E053"
        withClue(diagnostic.message) {
            diagnostic.message shouldContain "computed expression of 'line_total'"
            diagnostic.message shouldContain "mssql"
        }
    }

    test("the same expression is fine on the dialect it came from") {
        val op = addColumn("op-1", ColumnDefinition(NeutralType.Decimal(14, 2), generation = foreignComputed))
        val plan = planOf(op)

        val guarded = MigrateRawSqlPortabilityGuard.apply(
            rendered("op-1"), plan, DatabaseDialect.POSTGRESQL, assess,
        )

        guarded.blockers.shouldBeEmpty()
        guarded.primaryBlockedReason.shouldBeNull()
    }

    test("an operation that was not rendered needs no second refusal") {
        val op = addColumn("op-1", ColumnDefinition(NeutralType.Decimal(14, 2), generation = foreignComputed))
        val plan = planOf(op)

        // `operationsRendered` ist leer — der Renderer hat sie schon uebersprungen.
        val guarded = MigrateRawSqlPortabilityGuard.apply(
            rendered(), plan, DatabaseDialect.MSSQL, assess,
        )

        guarded.blockers.shouldBeEmpty()
        guarded.diagnostics.shouldBeEmpty()
    }

    test("without a bound assessor nothing is judged") {
        val op = addColumn("op-1", ColumnDefinition(NeutralType.Decimal(14, 2), generation = foreignComputed))

        val guarded = MigrateRawSqlPortabilityGuard.apply(
            rendered("op-1"), planOf(op), DatabaseDialect.MSSQL, null,
        )

        guarded.blockers.shouldBeEmpty()
    }

    test("a CREATE TABLE is searched in all four places raw text can hide") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(NeutralType.BigInteger, required = true),
                "total" to ColumnDefinition(NeutralType.Decimal(14, 2), generation = foreignComputed),
                "status" to ColumnDefinition(
                    NeutralType.Text(),
                    default = DefaultValue.FunctionCall("ARRAY['NEW'::order_status]"),
                ),
            ),
            constraints = listOf(
                ConstraintDefinition(
                    name = "ck_mail",
                    type = ConstraintType.CHECK,
                    expression = "email ~~ 'x'::text",
                ),
            ),
            indices = listOf(
                IndexDefinition(
                    name = "ix_expr",
                    columns = listOf(IndexColumn(name = "lower(x)", expression = "lower(x::text)")),
                    where = "y::int > 0",
                ),
            ),
        )
        val op = DiffOperation.CreateTable(
            id = "op-t",
            objectRef = DiffObjectRef(DiffObjectType.TABLE, listOf("order_items")),
            table = table,
        )

        val guarded = MigrateRawSqlPortabilityGuard.apply(
            rendered("op-t"), planOf(op), DatabaseDialect.MYSQL, assess,
        )

        val fields = guarded.diagnostics.map { it.message }
        withClue(fields.toString()) {
            fields.count { it.contains("computed expression of 'total'") } shouldBe 1
            fields.count { it.contains("function default of 'status'") } shouldBe 1
            fields.count { it.contains("CHECK expression of 'ck_mail'") } shouldBe 1
            fields.count { it.contains("predicate of index 'ix_expr'") } shouldBe 1
            fields.count { it.contains("expression key of index 'ix_expr'") } shouldBe 1
        }
        guarded.blockers.single().operationIds shouldBe setOf("op-t")
    }

    test("a clean plan is returned untouched") {
        val op = addColumn(
            "op-1",
            ColumnDefinition(NeutralType.Decimal(14, 2), generation = ColumnGeneration.Computed("a * b")),
        )
        val plan = planOf(op)
        val input = rendered("op-1")

        val guarded = MigrateRawSqlPortabilityGuard.apply(input, plan, DatabaseDialect.MSSQL, assess)

        (guarded === input) shouldBe true
    }
})
