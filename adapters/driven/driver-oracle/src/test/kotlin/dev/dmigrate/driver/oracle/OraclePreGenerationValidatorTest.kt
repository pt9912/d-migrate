package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * oracle-doppelter-generierungsausdruck.md P1: der `schema generate`-Pfad
 * faengt beide gemessenen Ablehnungen ueber den generischen
 * [dev.dmigrate.driver.PreGenerationValidator]-Hook, bevor der Generator
 * ueberhaupt anfaengt.
 */
class OraclePreGenerationValidatorTest : FunSpec({

    val idCol = "id" to ColumnDefinition(NeutralType.Integer, required = true)

    fun schemaWith(table: TableDefinition) = SchemaDefinition(name = "App", version = "1", tables = mapOf("t" to table))

    test("a clean schema (no duplicate, no collision) is cleared") {
        val table = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
            ),
            primaryKey = listOf("id"),
        )
        OraclePreGenerationValidator.validate(schemaWith(table), DdlGenerationOptions()).shouldBeEmpty()
    }

    test("two computed columns with identical expression text -> E073, names both columns") {
        val table = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
                "y" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
            ),
            primaryKey = listOf("id"),
        )
        val errors = OraclePreGenerationValidator.validate(schemaWith(table), DdlGenerationOptions())
        errors shouldHaveSize 1
        errors.single().code shouldBe "E073"
        errors.single().message shouldContain "ORA-54015"
        errors.single().message shouldContain "'x'"
        errors.single().message shouldContain "'y'"
    }

    test("an expression index + a plain index on the same computed column -> E074") {
        val table = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(columns = listOf(IndexColumn.expression("\"id\" * 2"))),
                IndexDefinition(columns = listOf(IndexColumn(name = "x"))),
            ),
        )
        val errors = OraclePreGenerationValidator.validate(schemaWith(table), DdlGenerationOptions())
        errors shouldHaveSize 1
        errors.single().code shouldBe "E074"
        errors.single().message shouldContain "ORA-01408"
    }

    test("both violations on the same table are both reported") {
        val table = TableDefinition(
            columns = linkedMapOf(
                idCol,
                "x" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
                "y" to ColumnDefinition(NeutralType.Integer, generation = ColumnGeneration.Computed("\"id\" * 2")),
            ),
            primaryKey = listOf("id"),
            indices = listOf(
                IndexDefinition(columns = listOf(IndexColumn.expression("\"id\" * 2"))),
                IndexDefinition(columns = listOf(IndexColumn(name = "x"))),
            ),
        )
        val errors = OraclePreGenerationValidator.validate(schemaWith(table), DdlGenerationOptions())
        errors.map { it.code }.toSet() shouldBe setOf("E073", "E074")
    }
})
