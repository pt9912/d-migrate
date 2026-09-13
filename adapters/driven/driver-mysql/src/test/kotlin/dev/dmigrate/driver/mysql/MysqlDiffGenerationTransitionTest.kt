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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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

    test("making a key column computed is refused: the column carries the encumbrance a swap cannot cross") {
        val result = renderGenerationChange(null, ColumnGeneration.Computed("qty * 2", stored = false))
        result.statements.shouldBeEmpty()
        val message = result.diagnostics.single().message
        message shouldContain "part of the primary key"
        message shouldContain "Changing the STORED status"
    }

    // ── column-generation-diff-unmapped.md: Kind-Wechsel per Spaltentausch ──

    fun schemaWithLabel(generation: ColumnGeneration?) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "order_line" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "label" to ColumnDefinition(NeutralType.Text(), generation = generation),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    fun renderLabelGenerationChange(before: ColumnGeneration?, after: ColumnGeneration?) = gen.generateUp(
        planner.plan(
            schemaWithLabel(before),
            schemaWithLabel(after),
            SchemaDiff(
                tablesChanged = listOf(
                    TableDiff(
                        name = "order_line",
                        columnsChanged = listOf(ColumnDiff(name = "label", generation = ValueChange(before, after))),
                    ),
                ),
            ),
        ),
        DdlGenerationOptions(),
    )

    test("an ordinary column becomes computed via DROP + ADD, no data copy") {
        val computed = ColumnGeneration.Computed("CONCAT(id, '!')", stored = true)
        val result = renderLabelGenerationChange(null, computed)

        val sqls = result.statements.map { it.sql }
        sqls shouldHaveSize 2
        sqls[0] shouldContain "DROP COLUMN `label`"
        sqls[1] shouldContain "ADD COLUMN `label`"
        sqls[1] shouldContain "GENERATED ALWAYS AS (CONCAT(id, '!')) STORED"
        result.destructiveOperations shouldHaveSize 1
        result.requiresConfirmation shouldBe true
    }

    test("a computed column becomes ordinary via swap: add temp, copy, drop, rename, reassert") {
        val computed = ColumnGeneration.Computed("CONCAT(id, '!')", stored = true)
        val result = renderLabelGenerationChange(computed, null)

        val sqls = result.statements.map { it.sql }
        sqls shouldHaveSize 5
        sqls[0] shouldContain "ADD COLUMN `label__dmg_swap`"
        sqls[1] shouldBe "UPDATE `order_line` SET `label__dmg_swap` = `label`;"
        sqls[2] shouldContain "DROP COLUMN `label`"
        sqls[3] shouldBe "ALTER TABLE `order_line` RENAME COLUMN `label__dmg_swap` TO `label`;"
        sqls[4] shouldContain "MODIFY COLUMN `label`"
        sqls[4] shouldNotContain "GENERATED ALWAYS"
        result.destructiveOperations shouldHaveSize 1
    }

    test("the temp column in a computed-to-ordinary swap is nullable with no default, even for a required target") {
        val requiredOrdinary = null
        val computed = ColumnGeneration.Computed("CONCAT(id, '!')", stored = true)
        val result = gen.generateUp(
            planner.plan(
                SchemaDefinition(
                    name = "App", version = "1",
                    tables = mapOf(
                        "order_line" to TableDefinition(
                            columns = linkedMapOf(
                                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                                "label" to ColumnDefinition(NeutralType.Text(), generation = computed),
                            ),
                            primaryKey = listOf("id"),
                        ),
                    ),
                ),
                SchemaDefinition(
                    name = "App", version = "1",
                    tables = mapOf(
                        "order_line" to TableDefinition(
                            columns = linkedMapOf(
                                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                                "label" to ColumnDefinition(NeutralType.Text(), required = true, generation = requiredOrdinary),
                            ),
                            primaryKey = listOf("id"),
                        ),
                    ),
                ),
                SchemaDiff(
                    tablesChanged = listOf(
                        TableDiff(
                            name = "order_line",
                            columnsChanged = listOf(ColumnDiff(name = "label", generation = ValueChange(computed, null))),
                        ),
                    ),
                ),
            ),
            DdlGenerationOptions(),
        )
        val sqls = result.statements.map { it.sql }
        sqls[0] shouldNotContain "NOT NULL"
        sqls.last() shouldContain "MODIFY COLUMN `label`"
        sqls.last() shouldContain "NOT NULL"
    }
})
