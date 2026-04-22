package dev.dmigrate.core.validation

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SchemaValidatorExtendedTest : FunSpec({

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

    // ─── Warnings & Triggers & Views ────────────────────────────

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
        val gt = GeometryType("hexagon")
        gt.isKnown() shouldBe false
        gt.schemaName shouldBe "hexagon"
    }

    // ─── Reverse-taugliche Schemas ──────────────────────────────

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

    // ─── SequenceNextVal (0.9.3) ────────────────────────────────

    test("E009: SequenceNextVal on autoIncrement identifier is incompatible") {
        val schema = SchemaDefinition(
            name = "T", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(
                    type = NeutralType.Identifier(autoIncrement = true),
                    default = DefaultValue.SequenceNextVal("my_seq"),
                )),
                primaryKey = listOf("id"),
            )),
        )
        val result = SchemaValidator().validate(schema)
        result.errors.any { it.code == "E009" } shouldBe true
    }

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
