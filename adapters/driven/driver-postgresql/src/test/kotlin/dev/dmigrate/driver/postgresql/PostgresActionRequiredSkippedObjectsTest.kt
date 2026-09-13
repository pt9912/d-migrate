package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Ein Objekt, das nicht in die Ausgabe kommt, steht in `skippedObjects` —
 * nicht nur als Notiz. Vor diesem Test trug `generateConstraintClause` kein
 * `skipped`, und eine CHECK-Constraint mit fremder Grammatik verschwand
 * spurlos aus dem Bestand, den der Aufrufer zaehlen kann (der urspruengliche
 * Repro: `skipped_objects: 0` bei tatsaechlich uebersprungener Constraint).
 */
class PostgresActionRequiredSkippedObjectsTest : FunSpec({

    val generator = PostgresDdlGenerator()

    test("eine CHECK-Constraint mit fremder Grammatik steht in skippedObjects") {
        val schema = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf(
                "orders" to TableDefinition(
                    columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "ck_backtick", type = ConstraintType.CHECK,
                            expression = "`id` > 0",
                        ),
                    ),
                ),
            ),
        )
        val result = generator.generate(schema)
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "constraint"
        skipped.name shouldBe "ck_backtick"
        skipped.code shouldBe "E053"
    }

    test("eine berechnete Spalte mit fremder Grammatik steht in skippedObjects") {
        val schema = SchemaDefinition(
            name = "t", version = "1.0",
            tables = mapOf(
                "order_items" to TableDefinition(
                    columns = mapOf(
                        "id" to ColumnDefinition(NeutralType.Integer, required = true),
                        "total" to ColumnDefinition(
                            NeutralType.Decimal(12, 2),
                            generation = ColumnGeneration.Computed("`qty` * `price`", stored = true),
                        ),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val result = generator.generate(schema)
        result.skippedObjects shouldHaveSize 1
        val skipped = result.skippedObjects.single()
        skipped.type shouldBe "computed_expression"
        skipped.name shouldBe "total"
        skipped.code shouldBe "E053"
    }
})
