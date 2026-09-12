package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.ColumnDiff
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TableDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Die Uebergaenge einer `AlterColumnGeneration`, die **kein** Ausdruckswechsel
 * sind — auf MySQL, live gegen 9.7.2 gemessen:
 *
 * | Uebergang | MySQL |
 * | --- | --- |
 * | Schluesselspalte → `AUTO_INCREMENT` | laeuft; der Zaehler setzt ueber dem Bestand auf |
 * | `AUTO_INCREMENT` → gewoehnlich | laeuft; Werte bleiben |
 * | ohne Schluessel → `AUTO_INCREMENT` | „there can be only one auto column and it must be defined as a key" |
 * | berechnet ↔ gewoehnlich | „'Changing the STORED status' is not supported" |
 */
class MysqlDiffGenerationTransitionTest : FunSpec({

    val gen = MysqlDiffDdlGenerator()
    val planner = DiffPlanner()

    fun schema(generation: ColumnGeneration?, keyed: Boolean) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true, generation = generation),
                    "label" to ColumnDefinition(NeutralType.Text()),
                ),
                primaryKey = if (keyed) listOf("id") else emptyList(),
            ),
        ),
    )

    fun renderGenerationChange(
        before: ColumnGeneration?,
        after: ColumnGeneration?,
        keyed: Boolean = true,
    ) = gen.generateUp(
        planner.plan(
            schema(before, keyed),
            schema(after, keyed),
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "order_line",
                        columnsChanged = listOf(ColumnDiff(name = "id", generation = ValueChange(before, after))),
                    ),
                ),
            ),
        ),
        DdlGenerationOptions(),
    )

    val identity = ColumnGeneration.Identity(IdentityMode.BY_DEFAULT)

    test("a key column becomes AUTO_INCREMENT, with type and nullability carried along") {
        val sql = renderGenerationChange(null, identity).statements.single().sql
        sql shouldContain "MODIFY COLUMN `id`"
        sql shouldContain "NOT NULL AUTO_INCREMENT"
    }

    test("AUTO_INCREMENT is dropped by rendering the declaration without it") {
        val sql = renderGenerationChange(identity, null).statements.single().sql
        sql shouldContain "MODIFY COLUMN `id`"
        sql shouldContain "NOT NULL"
        sql shouldNotContain "AUTO_INCREMENT"
    }

    test("without a key MySQL refuses, and the message says so") {
        val result = renderGenerationChange(null, identity, keyed = false)
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "not part of the primary key"
        message shouldContain "must be defined as a key"
    }

    test("swapping identity for a computation is refused with the server's wording") {
        val result = renderGenerationChange(identity, ColumnGeneration.Computed("qty * 2", stored = false))
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "swaps identity and computation"
        message shouldContain "Changing the STORED status"
    }

    test("making an ordinary column computed stays refused") {
        val result = renderGenerationChange(null, ColumnGeneration.Computed("qty * 2", stored = false))
        result.statements.shouldBeEmpty()
        result.diagnostics.single().message shouldContain "Changing the STORED status"
    }
})
