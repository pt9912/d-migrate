package dev.dmigrate.core.diff

import dev.dmigrate.core.identity.ObjectKeyCodec
import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class SchemaComparatorTestPart3 : FunSpec({

    val comparator = SchemaComparator()

    // --- Builder helpers ---

    fun schema(
        name: String = "Test",
        version: String = "1.0",
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = name,
        version = version,
        tables = tables,
        customTypes = customTypes,
        views = views,
        sequences = sequences,
        functions = functions,
        procedures = procedures,
        triggers = triggers,
    )

    fun table(
        columns: Map<String, ColumnDefinition> = emptyMap(),
        primaryKey: List<String> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
    ) = TableDefinition(
        columns = columns,
        primaryKey = primaryKey,
        indices = indices,
        constraints = constraints,
    )

    fun col(
        type: NeutralType = NeutralType.Text(),
        required: Boolean = false,
        unique: Boolean = false,
        default: DefaultValue? = null,
        references: ReferenceDefinition? = null,
    ) = ColumnDefinition(type = type, required = required, unique = unique,
        default = default, references = references)

    fun enumType(vararg values: String) =
        CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values.toList())

    fun view(
        materialized: Boolean = false,
        refresh: String? = null,
        query: String? = null,
        sourceDialect: String? = null,
    ) = ViewDefinition(
        materialized = materialized,
        refresh = refresh,
        query = query,
        sourceDialect = sourceDialect,
    )

    // ===========================================
    // 8.1 Pflichtfaelle
    // ===========================================


    test("table metadata engine change detected") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "InnoDB"),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "MyISAM"),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].metadata.shouldNotBeNull()
        diff.tablesChanged[0].metadata!!.before shouldBe TableMetadata(engine = "InnoDB")
        diff.tablesChanged[0].metadata!!.after shouldBe TableMetadata(engine = "MyISAM")
    }

    test("table metadata withoutRowid change detected") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(withoutRowid = false),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(withoutRowid = true),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].metadata.shouldNotBeNull()
    }

    test("identical table metadata produces no diff") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "InnoDB"),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "InnoDB"),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged.shouldBeEmpty()
    }
})
