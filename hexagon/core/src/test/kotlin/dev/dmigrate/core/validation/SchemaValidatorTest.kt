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

    test("W001: FLOAT column with monetary name generates warning") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "total_price" to col(NeutralType.Float())
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.warnings.any { it.code == "W001" } shouldBe true
    }

    test("W001: FLOAT column with non-monetary name does NOT generate warning") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "latitude" to col(NeutralType.Float())
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.warnings.none { it.code == "W001" } shouldBe true
    }

    test("E018: trigger references non-existent table") {
        val s = SchemaDefinition(
            name = "Test", version = "1.0",
            tables = mapOf(
                "items" to table(
                    columns = mapOf("id" to col(NeutralType.Identifier(true))),
                    primaryKey = listOf("id")
                )
            ),
            triggers = mapOf(
                "trg_test" to TriggerDefinition(
                    table = "missing_table",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER
                )
            )
        )
        val result = validator.validate(s)
        result.errors.any { it.code == "E018" } shouldBe true
    }

    test("E020: declared view dependency references non-existent view") {
        val s = schema(
            views = mapOf(
                "summary" to ViewDefinition(
                    query = "SELECT * FROM base_view",
                    dependencies = DependencyInfo(
                        tables = listOf("orders"),
                        views = listOf("base_view"),
                    ),
                    sourceDialect = "postgresql",
                )
            )
        )

        val result = validator.validate(s)

        result.errors.any {
            it.code == "E020" &&
                it.objectPath == "views.summary.dependencies.views"
        } shouldBe true
    }

    test("declared view dependency to existing view validates successfully") {
        val s = schema(
            views = mapOf(
                "base_view" to ViewDefinition(
                    query = "SELECT 1",
                    sourceDialect = "postgresql",
                ),
                "summary" to ViewDefinition(
                    query = "SELECT * FROM base_view",
                    dependencies = DependencyInfo(views = listOf("base_view")),
                    sourceDialect = "postgresql",
                ),
            )
        )

        val result = validator.validate(s)

        result.errors.none { it.code == "E020" } shouldBe true
    }

    test("trigger referencing existing table validates successfully") {
        val s = SchemaDefinition(
            name = "Test", version = "1.0",
            tables = mapOf(
                "items" to table(
                    columns = mapOf("id" to col(NeutralType.Identifier(true))),
                    primaryKey = listOf("id")
                )
            ),
            triggers = mapOf(
                "trg_test" to TriggerDefinition(
                    table = "items",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER
                )
            )
        )
        val result = validator.validate(s)
        result.errors.none { it.code == "E018" } shouldBe true
    }

    test("check expression with string literals does not produce false E012") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "status" to col(NeutralType.Text(50))
                ),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "chk_status", type = ConstraintType.CHECK,
                    expression = "status IN ('active', 'deleted')"
                ))
            )
        ))
        val result = validator.validate(s)
        result.errors.none { it.code == "E012" } shouldBe true
    }

    test("array with invalid element_type name produces E015") {
        val s = schema(tables = mapOf(
            "items" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "tags" to col(NeutralType.Array(elementType = "money"))
                ),
                primaryKey = listOf("id")
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E015" } shouldBe true
    }

    // ─── Spatial Phase 1 (0.5.5) ────────────────────────────────

    test("valid geometry with default geometry_type produces no errors") {
        val s = schema(tables = mapOf(
            "places" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "location" to col(NeutralType.Geometry()),
                ),
                primaryKey = listOf("id"),
            )
        ))
        val result = validator.validate(s)
        result.isValid shouldBe true
    }

    test("valid geometry with explicit geometry_type and srid produces no errors") {
        val s = schema(tables = mapOf(
            "places" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "location" to col(NeutralType.Geometry(
                        geometryType = GeometryType("point"),
                        srid = 4326,
                    )),
                ),
                primaryKey = listOf("id"),
            )
        ))
        val result = validator.validate(s)
        result.isValid shouldBe true
    }

    test("E120: unknown geometry_type") {
        val s = schema(tables = mapOf(
            "places" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "location" to col(NeutralType.Geometry(
                        geometryType = GeometryType("circle"),
                    )),
                ),
                primaryKey = listOf("id"),
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E120" } shouldBe true
    }

    test("E121: srid is 0") {
        val s = schema(tables = mapOf(
            "places" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "location" to col(NeutralType.Geometry(srid = 0)),
                ),
                primaryKey = listOf("id"),
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E121" } shouldBe true
    }

    test("E121: negative srid") {
        val s = schema(tables = mapOf(
            "places" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "location" to col(NeutralType.Geometry(srid = -1)),
                ),
                primaryKey = listOf("id"),
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E121" } shouldBe true
    }

    test("geometry is not accepted as array element_type") {
        val s = schema(tables = mapOf(
            "geo" to table(
                columns = mapOf(
                    "id" to col(NeutralType.Identifier(true)),
                    "shapes" to col(NeutralType.Array("geometry")),
                ),
                primaryKey = listOf("id"),
            )
        ))
        val result = validator.validate(s)
        result.errors.any { it.code == "E015" } shouldBe true
    }

    test("unknown geometry_type is preserved until validator check") {
        // The GeometryType value type must not reject unknown values at construction
        val gt = GeometryType("hexagon")
        gt.isKnown() shouldBe false
        gt.schemaName shouldBe "hexagon"
    }

    // ─── Phase B: Reverse-taugliche Schemas ─────────────────────

    test("reverse-generated schema with all object types validates without errors") {
        val s = SchemaDefinition(
            name = "Reverse DB", version = "1.0",
            tables = mapOf(
                "users" to TableDefinition(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(true)),
                        "name" to col(NeutralType.Text(100)),
                    ),
                    primaryKey = listOf("id"),
                ),
            ),
            customTypes = mapOf(
                "status" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b")),
                "posint" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0"),
                "address" to CustomTypeDefinition(
                    kind = CustomTypeKind.COMPOSITE,
                    fields = mapOf("street" to col(NeutralType.Text(200))),
                ),
            ),
            sequences = mapOf(
                "id_seq" to SequenceDefinition(start = 1, increment = 1),
            ),
            views = mapOf(
                "active_users" to ViewDefinition(query = "SELECT * FROM users", sourceDialect = "postgresql"),
            ),
            functions = mapOf(
                "calc(in:integer)" to FunctionDefinition(
                    parameters = listOf(ParameterDefinition("x", "integer")),
                    returns = ReturnType("integer"),
                    body = "RETURN x * 2;",
                    language = "sql",
                    sourceDialect = "postgresql",
                ),
            ),
            procedures = mapOf(
                "cleanup(in:integer)" to ProcedureDefinition(
                    parameters = listOf(ParameterDefinition("days", "integer")),
                    body = "DELETE FROM log WHERE age > days;",
                    language = "sql",
                    sourceDialect = "postgresql",
                ),
            ),
            triggers = mapOf(
                "users::audit" to TriggerDefinition(
                    table = "users",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    body = "INSERT INTO log VALUES (NEW.id);",
                    sourceDialect = "postgresql",
                ),
            ),
        )
        val result = validator.validate(s)
        result.isValid shouldBe true
    }

    test("reverse-generated schema without primary key is valid with E008 warning") {
        val s = SchemaDefinition(
            name = "Reverse DB", version = "1.0",
            tables = mapOf(
                "log" to TableDefinition(
                    columns = mapOf(
                        "ts" to col(NeutralType.DateTime()),
                        "msg" to col(NeutralType.Text()),
                    ),
                ),
            ),
            sequences = mapOf(
                "log_seq" to SequenceDefinition(start = 100),
            ),
        )
        val result = validator.validate(s)
        result.isValid shouldBe true
        result.warnings.any { it.code == "E008" } shouldBe true
    }

    test("schema with table metadata validates without errors") {
        val s = schema(tables = mapOf(
            "items" to TableDefinition(
                columns = mapOf("id" to col(NeutralType.Identifier(true))),
                primaryKey = listOf("id"),
                metadata = TableMetadata(engine = "InnoDB"),
            ),
            "config" to TableDefinition(
                columns = mapOf("key" to col(NeutralType.Text(50))),
                primaryKey = listOf("key"),
                metadata = TableMetadata(withoutRowid = true),
            ),
        ))
        val result = validator.validate(s)
        result.isValid shouldBe true
    }

    // ─── SequenceNextVal (0.9.3 AP 6.3) ─────────────────────────

    test("E009: SequenceNextVal on text column is type-incompatible") {
        val schema = SchemaDefinition(
            name = "T", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("name" to ColumnDefinition(
                    type = NeutralType.Text(),
                    default = DefaultValue.SequenceNextVal("my_seq"),
                )),
                primaryKey = listOf("name"),
            )),
        )
        val result = SchemaValidator().validate(schema)
        result.errors.any { it.code == "E009" } shouldBe true
    }

    test("SequenceNextVal on integer column with existing sequence passes") {
        val schema = SchemaDefinition(
            name = "T", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("my_seq"),
                )),
                primaryKey = listOf("id"),
            )),
        )
        val result = SchemaValidator().validate(schema)
        result.errors.filter { it.code in setOf("E009", "E122", "E123") }.shouldBeEmpty()
    }

    test("E122: legacy nextval FunctionCall triggers migration error") {
        val schema = SchemaDefinition(
            name = "T", version = "1",
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.FunctionCall("nextval('my_seq')"),
                )),
                primaryKey = listOf("id"),
            )),
        )
        val result = SchemaValidator().validate(schema)
        val e122 = result.errors.filter { it.code == "E122" }
        e122 shouldHaveSize 1
        e122.first().message shouldContain "sequence_nextval"
    }

    test("E123: SequenceNextVal references non-existent sequence") {
        val schema = SchemaDefinition(
            name = "T", version = "1",
            sequences = mapOf("other_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal("missing_seq"),
                )),
                primaryKey = listOf("id"),
            )),
        )
        val result = SchemaValidator().validate(schema)
        val e123 = result.errors.filter { it.code == "E123" }
        e123 shouldHaveSize 1
        e123.first().message shouldContain "missing_seq"
    }
})
