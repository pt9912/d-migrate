package dev.dmigrate.core.validation

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.DependencyInfo
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.core.model.ViewDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SchemaStructureValidationRulesTest : FunSpec({

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "Test",
        version = "1.0",
        tables = tables,
        views = views,
        triggers = triggers,
    )

    fun table(
        columns: Map<String, ColumnDefinition>,
        primaryKey: List<String> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
    ) = TableDefinition(
        columns = columns,
        primaryKey = primaryKey,
        indices = indices,
        constraints = constraints,
    )

    fun col(type: NeutralType) = ColumnDefinition(type = type)

    test("reports table and check-expression structure errors") {
        val result = SchemaStructureValidationRules.validate(
            schema(
                tables = mapOf(
                    "items" to table(
                        columns = mapOf("id" to col(NeutralType.Identifier(true))),
                        indices = listOf(IndexDefinition(name = "idx_bad", columns = listOf("missing"))),
                        constraints = listOf(
                            ConstraintDefinition(
                                name = "chk_status",
                                type = ConstraintType.CHECK,
                                expression = "missing_column >= 0",
                            ),
                        ),
                    ),
                    "empty" to table(columns = emptyMap()),
                ),
            ),
        )

        result.errors.map { it.code }.sorted() shouldBe listOf("E001", "E005", "E012")
    }

    test("reports float monetary warning and missing view dependency") {
        val result = SchemaStructureValidationRules.validate(
            schema(
                tables = mapOf(
                    "payments" to table(
                        columns = mapOf(
                            "id" to col(NeutralType.Identifier(true)),
                            "total_price" to col(NeutralType.Float()),
                        ),
                    ),
                ),
                views = mapOf(
                    "summary" to ViewDefinition(
                        query = "SELECT * FROM base_view",
                        dependencies = DependencyInfo(views = listOf("base_view")),
                        sourceDialect = "postgresql",
                    ),
                ),
            ),
        )

        result.warnings.map { it.code } shouldBe listOf("W001")
        result.errors.map { it.code } shouldBe listOf("E020")
    }

    test("reports trigger reference error without affecting warnings") {
        val result = SchemaStructureValidationRules.validate(
            schema(
                tables = mapOf(
                    "items" to table(
                        columns = mapOf("id" to col(NeutralType.Identifier(true))),
                        primaryKey = listOf("id"),
                    ),
                ),
                triggers = mapOf(
                    "trg_items" to TriggerDefinition(
                        table = "missing_table",
                        event = TriggerEvent.INSERT,
                        timing = TriggerTiming.AFTER,
                    ),
                ),
            ),
        )

        result.errors.map { it.code } shouldBe listOf("E018")
        result.warnings.isEmpty() shouldBe true
    }
})
