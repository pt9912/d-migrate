package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Ein Objekt, das nicht in die Ausgabe kommt, steht in `skippedObjects` —
 * nicht nur als Notiz. Drei Faelle, die vor diesem Test spurlos verschwanden:
 * eine CHECK-Constraint und ein EXCLUDE-Constraint mit fremder Grammatik
 * (`generateConstraintClause` trug kein `skipped`), und ein COMPOSITE-Typ
 * (`generateCustomTypes` trug gar keinen `skipped`-Parameter).
 */
class SqliteActionRequiredSkippedObjectsTest : FunSpec({

    val generator = SqliteDdlGenerator()

    fun schemaWith(table: TableDefinition) = SchemaDefinition(
        name = "t", version = "1.0", tables = mapOf("orders" to table),
    )

    test("eine CHECK-Constraint mit fremder Grammatik steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "ck_cast", type = ConstraintType.CHECK, expression = "id::int > 0"),
            ),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "ck_cast"
        skipped.code shouldBe "E053"
    }

    test("ein EXCLUDE-Constraint (in SQLite grundsaetzlich unmoeglich) steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(name = "no_overlap", type = ConstraintType.EXCLUDE, expression = "id WITH ="),
            ),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "no_overlap"
        skipped.code shouldBe "E054"
    }

    test("eine berechnete Spalte mit fremder Grammatik steht in skippedObjects") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(NeutralType.Integer, required = true),
                "total" to ColumnDefinition(
                    NeutralType.Decimal(12, 2),
                    generation = ColumnGeneration.Computed("qty::int * price::int", stored = true),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schemaWith(table))
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "computed_expression"
        skipped.name shouldBe "total"
        skipped.code shouldBe "E053"
    }

    test("ein COMPOSITE-Typ (in SQLite grundsaetzlich unmoeglich) steht in skippedObjects") {
        val schema = SchemaDefinition(
            name = "t", version = "1.0",
            tables = emptyMap(),
            customTypes = mapOf(
                "address" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE),
            ),
        )
        val result = generator.generate(schema)
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "custom_type"
        skipped.name shouldBe "address"
        skipped.code shouldBe "E054"
    }
})
