package dev.dmigrate.core.validation

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SchemaValidatorTest : FunSpec({

    val validator = SchemaValidator()

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "Test",
        version = "1.0",
        tables = tables,
        customTypes = customTypes,
        views = views,
    )

    fun table(columns: Map<String, ColumnDefinition>,
              primaryKey: List<String> = emptyList(),
              indices: List<IndexDefinition> = emptyList(),
              constraints: List<ConstraintDefinition> = emptyList(),
              partitioning: PartitionConfig? = null) =
        TableDefinition(columns = columns, primaryKey = primaryKey, indices = indices,
            constraints = constraints, partitioning = partitioning)

    fun col(type: NeutralType, required: Boolean = false, unique: Boolean = false,
            default: DefaultValue? = null, references: ReferenceDefinition? = null) =
        ColumnDefinition(type = type, required = required, unique = unique,
            default = default, references = references)

    test("valid schema produces no errors") {
        val s = schema(tables = mapOf(
            "users" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true)), "name" to col(NeutralType.Text(100))),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.isValid shouldBe true
        result.errors.shouldBeEmpty()
    }

    test("E001: table has no columns") {
        val s = schema(tables = mapOf(
            "empty" to table(columns = emptyMap(), primaryKey = listOf("id"))
        ))
        val result = validator.validate(s)
        result.errors.shouldHaveSize(1)
        result.errors[0].code shouldBe "E001"
    }

    test("E002: FK references non-existent table") {
        val s = schema(tables = mapOf(
            "orders" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "customer_id" to col(NeutralType.Integer,
                        references = ReferenceDefinition("missing_table", "id"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E002" } shouldBe true
    }

    test("E003: FK references non-existent column") {
        val s = schema(tables = mapOf(
            "customers" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true))),
                primaryKey = listOf("id")
            ),
            "orders" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "customer_id" to col(NeutralType.Integer,
                        references = ReferenceDefinition("customers", "missing_col"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E003" } shouldBe true
    }

    test("E005: index references non-existent column") {
        val s = schema(tables = mapOf(
            "users" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true))),
                primaryKey = listOf("id"),
                indices = listOf(IndexDefinition(name = "idx_bad", columns = listOf("nonexistent")))
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E005" } shouldBe true
    }

    test("E006: enum values empty") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "status" to col(NeutralType.Enum())
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E006" } shouldBe true
    }

    test("E007: ref_type references non-existent custom type") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "status" to col(NeutralType.Enum(refType = "missing_type"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E007" } shouldBe true
    }

    test("E008: table without primary key produces warning, not error") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf("name" to col(NeutralType.Text(100)))
            )
        ))
        val result = validator.validate(s)
        result.isValid shouldBe true
        result.warnings.any { it.code == "E008" } shouldBe true
        result.errors.none { it.code == "E008" } shouldBe true
    }

    test("E008: identifier column satisfies primary key requirement — no warning") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true)), "name" to col(NeutralType.Text(100)))
            )
        ))
        val result = validator.validate(s)
        result.warnings.none { it.code == "E008" } shouldBe true
    }

    test("E009: default value incompatible with column type") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "quantity" to col(NeutralType.Integer, default = DefaultValue.BooleanLiteral(true))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E009" } shouldBe true
    }

    test("E010: decimal with invalid precision") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "price" to col(NeutralType.Decimal(precision = 0, scale = -1))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E010" } shouldBe true
    }

    test("E011: max_length not positive") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "name" to col(NeutralType.Text(maxLength = -1))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E011" } shouldBe true
    }

    test("E012: check expression references unknown column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "total" to col(NeutralType.Decimal(10, 2))
                ),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "chk_test", type = ConstraintType.CHECK,
                    expression = "nonexistent_column >= 0"
                ))
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E012" } shouldBe true
    }

    test("E013: enum has both ref_type and values") {
        val s = schema(
            customTypes = mapOf("status" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a"))),
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(true)),
                        "status" to col(NeutralType.Enum(values = listOf("a", "b"), refType = "status"))
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = validator.validate(s)
        result.errors.any { it.code == "E013" } shouldBe true
    }

    test("E014: char length not positive") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "code" to col(NeutralType.Char(length = 0))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E014" } shouldBe true
    }

    test("E015: array element_type is blank") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "tags" to col(NeutralType.Array(elementType = ""))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E015" } shouldBe true
    }

    test("E016: partition key references non-existent column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true))),
                primaryKey = listOf("id"),
                partitioning = PartitionConfig(type = PartitionType.RANGE, key = listOf("missing_col"))
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E016" } shouldBe true
    }

    test("E017: FK type incompatible with referenced column") {
        val s = schema(tables = mapOf(
            "categories" to table(
                columns = mapOf("id" to col(NeutralType.Text(100))),
                primaryKey = listOf("id")
            ),
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "category_id" to col(NeutralType.Integer,
                        references = ReferenceDefinition("categories", "id"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E017" } shouldBe true
    }

    test("valid FK between integer and identifier is compatible") {
        val s = schema(tables = mapOf(
            "parents" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true))),
                primaryKey = listOf("id")
            ),
            "children" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "parent_id" to col(NeutralType.Integer,
                        references = ReferenceDefinition("parents", "id"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E017" } shouldBe true
    }

    test("valid FK between biginteger and identifier is compatible") {
        val s = schema(tables = mapOf(
            "parents" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true))),
                primaryKey = listOf("id")
            ),
            "children" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "parent_id" to col(NeutralType.BigInteger,
                        references = ReferenceDefinition("parents", "id"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E017" } shouldBe true
    }

    test("valid string default on text column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "name" to col(NeutralType.Text(100), default = DefaultValue.StringLiteral("unknown")),
                    "code" to col(NeutralType.Char(2), default = DefaultValue.StringLiteral("XX")),
                    "mail" to col(NeutralType.Email, default = DefaultValue.StringLiteral("a@b.c")),
                    "uid" to col(NeutralType.Uuid, default = DefaultValue.StringLiteral("abc")),
                    "status" to col(NeutralType.Enum(values = listOf("a", "b")), default = DefaultValue.StringLiteral("a"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E009" } shouldBe true
    }

    test("valid number default on numeric columns") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true), default = DefaultValue.NumberLiteral(1)),
                    "qty" to col(NeutralType.Integer, default = DefaultValue.NumberLiteral(0)),
                    "small" to col(NeutralType.SmallInt, default = DefaultValue.NumberLiteral(0)),
                    "big" to col(NeutralType.BigInteger, default = DefaultValue.NumberLiteral(0)),
                    "price" to col(NeutralType.Float(), default = DefaultValue.NumberLiteral(0.0)),
                    "amount" to col(NeutralType.Decimal(10, 2), default = DefaultValue.NumberLiteral(0))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E009" } shouldBe true
    }

    test("valid boolean default on boolean column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "active" to col(NeutralType.BooleanType, default = DefaultValue.BooleanLiteral(true))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E009" } shouldBe true
    }

    test("valid function defaults") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "created" to col(NeutralType.DateTime(), default = DefaultValue.FunctionCall("current_timestamp")),
                    "day" to col(NeutralType.Date, default = DefaultValue.FunctionCall("current_timestamp")),
                    "hour" to col(NeutralType.Time, default = DefaultValue.FunctionCall("current_timestamp")),
                    "uid" to col(NeutralType.Uuid, default = DefaultValue.FunctionCall("gen_uuid")),
                    "other" to col(NeutralType.Text(), default = DefaultValue.FunctionCall("custom_func"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E009" } shouldBe true
    }

    test("E009: number default on text column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "name" to col(NeutralType.Text(100), default = DefaultValue.NumberLiteral(42))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E009" } shouldBe true
    }

    test("E009: gen_uuid on integer column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "num" to col(NeutralType.Integer, default = DefaultValue.FunctionCall("gen_uuid"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E009" } shouldBe true
    }

    test("schema with no tables validates successfully") {
        val s = schema()
        val result = validator.validate(s)
        result.isValid shouldBe true
    }

    test("multiple errors accumulated in single validation") {
        val s = schema(tables = mapOf(
            "bad" to table(
                columns = emptyMap(),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E001" } shouldBe true
    }

    test("valid check expression referencing existing column") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "total" to col(NeutralType.Decimal(10, 2))
                ),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "chk_pos", type = ConstraintType.CHECK,
                    expression = "total >= 0"
                ))
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E012" } shouldBe true
    }
})
