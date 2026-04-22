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

class SchemaComparatorTestPart2 : FunSpec({

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


    test("repeated comparator run produces same result") {
        val left = schema(
            tables = mapOf(
                "b" to table(columns = mapOf("x" to col())),
                "a" to table(columns = mapOf("y" to col(NeutralType.Integer))),
            ),
            customTypes = mapOf(
                "z" to enumType("a", "b"),
                "m" to enumType("x"),
            ),
            views = mapOf(
                "v2" to view(query = "SELECT 1"),
                "v1" to view(query = "SELECT old"),
            ),
        )
        val right = schema(
            tables = mapOf(
                "a" to table(columns = mapOf("y" to col(NeutralType.Text(50)))),
                "c" to table(columns = mapOf("z" to col())),
            ),
            customTypes = mapOf(
                "z" to enumType("a", "b", "c"),
                "n" to enumType("y"),
            ),
            views = mapOf(
                "v1" to view(query = "SELECT new"),
                "v3" to view(query = "SELECT 3"),
            ),
        )

        val first = comparator.compare(left, right)
        val second = comparator.compare(left, right)
        first shouldBe second
    }

    test("view description change does not produce diff") {
        val left = schema(views = mapOf("v" to ViewDefinition(
            description = "old desc", query = "SELECT 1",
        )))
        val right = schema(views = mapOf("v" to ViewDefinition(
            description = "new desc", query = "SELECT 1",
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("added tables are sorted by name") {
        val left = schema()
        val right = schema(tables = mapOf(
            "zebra" to table(columns = mapOf("id" to col())),
            "alpha" to table(columns = mapOf("id" to col())),
            "mid" to table(columns = mapOf("id" to col())),
        ))

        val diff = comparator.compare(left, right)
        diff.tablesAdded.map { it.name } shouldBe listOf("alpha", "mid", "zebra")
    }

    test("added column has unique and references stripped") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "email" to col(NeutralType.Email, unique = true,
                    references = ReferenceDefinition("other", "id")),
            ),
        )))

        val diff = comparator.compare(left, right)
        val addedCol = diff.tablesChanged[0].columnsAdded["email"]!!
        addedCol.unique shouldBe false
        addedCol.references shouldBe null
    }

    test("removed column has unique and references stripped") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "email" to col(NeutralType.Email, unique = true,
                    references = ReferenceDefinition("other", "id")),
            ),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))

        val diff = comparator.compare(left, right)
        val removedCol = diff.tablesChanged[0].columnsRemoved["email"]!!
        removedCol.unique shouldBe false
        removedCol.references shouldBe null
    }

    test("conflicting column-level and constraint-level FK preserves both") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("tableA", "id"),
            )),
            constraints = listOf(ConstraintDefinition(
                name = "fk_ref_b", type = ConstraintType.FOREIGN_KEY,
                columns = listOf("ref"),
                references = ConstraintReferenceDefinition(
                    table = "tableB", columns = listOf("id"),
                ),
            )),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("tableA", "id"),
            )),
            constraints = listOf(ConstraintDefinition(
                name = "fk_ref_b", type = ConstraintType.FOREIGN_KEY,
                columns = listOf("ref"),
                references = ConstraintReferenceDefinition(
                    table = "tableB", columns = listOf("id"),
                ),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("FK with different onDelete action is detected") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("other", "id",
                    onDelete = ReferentialAction.CASCADE),
            )),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("other", "id",
                    onDelete = ReferentialAction.RESTRICT),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].constraintsChanged shouldHaveSize 1
    }

    // ===========================================
    // Phase B: Extended object types
    // ===========================================

    test("domain custom type changed") {
        val left = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0",
            ),
        ))
        val right = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "biginteger", check = "VALUE > 0",
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].name shouldBe "posint"
        diff.customTypesChanged[0].baseType.shouldNotBeNull()
        diff.customTypesChanged[0].baseType!!.before shouldBe "integer"
        diff.customTypesChanged[0].baseType!!.after shouldBe "biginteger"
        diff.customTypesChanged[0].check.shouldBeNull()
    }

    test("domain custom type check changed") {
        val left = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0",
            ),
        ))
        val right = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE >= 0",
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].check.shouldNotBeNull()
    }

    test("composite custom type added") {
        val left = schema()
        val right = schema(customTypes = mapOf(
            "address" to CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf(
                    "street" to col(NeutralType.Text(200)),
                    "city" to col(NeutralType.Text(100)),
                ),
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesAdded shouldHaveSize 1
        diff.customTypesAdded[0].name shouldBe "address"
    }

    test("composite custom type fields changed") {
        val left = schema(customTypes = mapOf(
            "address" to CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf("street" to col(NeutralType.Text(200))),
            ),
        ))
        val right = schema(customTypes = mapOf(
            "address" to CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf(
                    "street" to col(NeutralType.Text(200)),
                    "city" to col(NeutralType.Text(100)),
                ),
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].fields.shouldNotBeNull()
    }

    test("sequence added and removed") {
        val left = schema(sequences = mapOf(
            "old_seq" to SequenceDefinition(start = 1, increment = 1),
        ))
        val right = schema(sequences = mapOf(
            "new_seq" to SequenceDefinition(start = 100, increment = 10),
        ))

        val diff = comparator.compare(left, right)
        diff.sequencesAdded shouldHaveSize 1
        diff.sequencesAdded[0].name shouldBe "new_seq"
        diff.sequencesRemoved shouldHaveSize 1
        diff.sequencesRemoved[0].name shouldBe "old_seq"
    }

    test("sequence changed") {
        val left = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(start = 1, increment = 1, cycle = false),
        ))
        val right = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(start = 1, increment = 1, cycle = true),
        ))

        val diff = comparator.compare(left, right)
        diff.sequencesChanged shouldHaveSize 1
        diff.sequencesChanged[0].name shouldBe "id_seq"
        diff.sequencesChanged[0].cycle.shouldNotBeNull()
        diff.sequencesChanged[0].cycle!!.before shouldBe false
        diff.sequencesChanged[0].cycle!!.after shouldBe true
    }

})
