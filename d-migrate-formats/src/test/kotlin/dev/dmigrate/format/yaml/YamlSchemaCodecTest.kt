package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.SchemaValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class YamlSchemaCodecTest : FunSpec({

    val codec = YamlSchemaCodec()
    val validator = SchemaValidator()

    fun loadFixture(path: String) =
        codec.read(YamlSchemaCodecTest::class.java.getResourceAsStream("/fixtures/$path")!!)

    test("parse minimal schema") {
        val schema = loadFixture("schemas/minimal.yaml")
        schema.name shouldBe "Minimal Schema"
        schema.version shouldBe "1.0.0"
        schema.tables shouldHaveSize 1
        schema.tables["users"] shouldNotBe null
        schema.tables["users"]!!.columns shouldHaveSize 2
        schema.tables["users"]!!.primaryKey shouldBe listOf("id")

        val idCol = schema.tables["users"]!!.columns["id"]!!
        (idCol.type as NeutralType.Identifier).autoIncrement shouldBe true
    }

    test("parse e-commerce schema") {
        val schema = loadFixture("schemas/e-commerce.yaml")
        schema.name shouldBe "E-Commerce System"
        schema.tables shouldHaveSize 2
        schema.customTypes shouldHaveSize 1

        val orders = schema.tables["orders"]!!
        orders.columns shouldHaveSize 6
        orders.indices shouldHaveSize 1
        orders.constraints shouldHaveSize 1

        val customerIdCol = orders.columns["customer_id"]!!
        customerIdCol.required shouldBe true
        customerIdCol.references shouldNotBe null
        customerIdCol.references!!.table shouldBe "customers"
        customerIdCol.references!!.onDelete shouldBe ReferentialAction.RESTRICT

        val statusCol = orders.columns["status"]!!
        val statusType = statusCol.type as NeutralType.Enum
        statusType.refType shouldBe "order_status"

        val customType = schema.customTypes["order_status"]!!
        customType.kind shouldBe CustomTypeKind.ENUM
        customType.values shouldBe listOf("pending", "processing", "shipped", "delivered", "cancelled")
    }

    test("parse all-types schema covers all 18 neutral types") {
        val schema = loadFixture("schemas/all-types.yaml")
        val cols = schema.tables["type_test"]!!.columns

        cols["col_identifier"]!!.type shouldBe NeutralType.Identifier(autoIncrement = true)
        cols["col_text"]!!.type shouldBe NeutralType.Text(maxLength = 255)
        cols["col_char"]!!.type shouldBe NeutralType.Char(length = 2)
        cols["col_integer"]!!.type shouldBe NeutralType.Integer
        cols["col_smallint"]!!.type shouldBe NeutralType.SmallInt
        cols["col_biginteger"]!!.type shouldBe NeutralType.BigInteger
        cols["col_float_single"]!!.type shouldBe NeutralType.Float(FloatPrecision.SINGLE)
        cols["col_float_double"]!!.type shouldBe NeutralType.Float(FloatPrecision.DOUBLE)
        cols["col_decimal"]!!.type shouldBe NeutralType.Decimal(precision = 10, scale = 2)
        cols["col_boolean"]!!.type shouldBe NeutralType.BooleanType
        (cols["col_datetime"]!!.type as NeutralType.DateTime).timezone shouldBe true
        cols["col_date"]!!.type shouldBe NeutralType.Date
        cols["col_time"]!!.type shouldBe NeutralType.Time
        cols["col_uuid"]!!.type shouldBe NeutralType.Uuid
        cols["col_json"]!!.type shouldBe NeutralType.Json
        cols["col_xml"]!!.type shouldBe NeutralType.Xml
        cols["col_binary"]!!.type shouldBe NeutralType.Binary
        cols["col_email"]!!.type shouldBe NeutralType.Email()
        (cols["col_enum"]!!.type as NeutralType.Enum).values shouldBe listOf("a", "b", "c")
        (cols["col_array"]!!.type as NeutralType.Array).elementType shouldBe "text"
    }

    test("e-commerce schema validates successfully") {
        val schema = loadFixture("schemas/e-commerce.yaml")
        val result = validator.validate(schema)
        result.isValid shouldBe true
    }

    test("all-types schema validates successfully") {
        val schema = loadFixture("schemas/all-types.yaml")
        val result = validator.validate(schema)
        result.isValid shouldBe true
    }

    test("parse full-featured schema with all object types") {
        val schema = loadFixture("schemas/full-featured.yaml")
        schema.name shouldBe "Full Featured Schema"
        schema.description shouldBe "Schema that exercises all parsing paths"
        schema.encoding shouldBe "utf-8"
        schema.locale shouldBe "de_DE"

        // Custom types
        schema.customTypes shouldHaveSize 3
        schema.customTypes["order_status"]!!.kind shouldBe CustomTypeKind.ENUM
        schema.customTypes["address"]!!.kind shouldBe CustomTypeKind.COMPOSITE
        schema.customTypes["address"]!!.fields shouldNotBe null
        schema.customTypes["address"]!!.fields!!.size shouldBe 2
        schema.customTypes["positive_amount"]!!.kind shouldBe CustomTypeKind.DOMAIN
        schema.customTypes["positive_amount"]!!.baseType shouldBe "decimal"
        schema.customTypes["positive_amount"]!!.check shouldBe "VALUE >= 0"

        // Tables
        schema.tables shouldHaveSize 2
        val orders = schema.tables["orders"]!!
        orders.partitioning shouldNotBe null
        orders.partitioning!!.type shouldBe PartitionType.RANGE
        orders.partitioning!!.key shouldBe listOf("date")
        orders.partitioning!!.partitions shouldHaveSize 2
        orders.partitioning!!.partitions[0].name shouldBe "orders_2024"
        orders.partitioning!!.partitions[0].from shouldBe "2024-01-01"
        orders.constraints shouldHaveSize 3
        orders.constraints[2].type shouldBe ConstraintType.FOREIGN_KEY
        orders.constraints[2].references shouldNotBe null
        orders.constraints[2].references!!.table shouldBe "customers"
        orders.constraints[2].references!!.onDelete shouldBe ReferentialAction.RESTRICT
        orders.constraints[2].references!!.onUpdate shouldBe ReferentialAction.CASCADE

        // FK with on_update
        val custRef = orders.columns["customer_id"]!!.references!!
        custRef.onDelete shouldBe ReferentialAction.CASCADE
        custRef.onUpdate shouldBe ReferentialAction.NO_ACTION

        // Default values
        val statusCol = orders.columns["status"]!!
        statusCol.default shouldBe DefaultValue.StringLiteral("pending")
        val dateCol = orders.columns["date"]!!
        dateCol.default shouldBe DefaultValue.FunctionCall("current_timestamp")
        val uuidCol = orders.columns["uuid_field"]!!
        uuidCol.default shouldBe DefaultValue.FunctionCall("gen_uuid")
        val activeCol = orders.columns["active"]!!
        activeCol.default shouldBe DefaultValue.BooleanLiteral(true)
        val amountCol = orders.columns["amount"]!!
        (amountCol.default as DefaultValue.NumberLiteral).value shouldBe 0

        // Procedures
        schema.procedures shouldHaveSize 1
        val proc = schema.procedures["update_status"]!!
        proc.description shouldBe "Update order status"
        proc.parameters shouldHaveSize 3
        proc.parameters[0].name shouldBe "p_order_id"
        proc.parameters[0].direction shouldBe ParameterDirection.IN
        proc.parameters[2].direction shouldBe ParameterDirection.OUT
        proc.language shouldBe "plpgsql"
        proc.body shouldNotBe null
        proc.sourceDialect shouldBe "postgresql"
        proc.dependencies shouldNotBe null
        proc.dependencies!!.tables shouldBe listOf("orders")
        proc.dependencies!!.columns["orders"] shouldBe listOf("id", "status")

        // Functions
        schema.functions shouldHaveSize 1
        val func = schema.functions["calc_total"]!!
        func.returns shouldNotBe null
        func.returns!!.type shouldBe "decimal"
        func.returns!!.precision shouldBe 10
        func.returns!!.scale shouldBe 2
        func.deterministic shouldBe false
        func.sourceDialect shouldBe "postgresql"

        // Views
        schema.views shouldHaveSize 2
        val activeOrders = schema.views["active_orders"]!!
        activeOrders.materialized shouldBe false
        activeOrders.query shouldNotBe null
        val monthlyStats = schema.views["monthly_stats"]!!
        monthlyStats.materialized shouldBe true
        monthlyStats.refresh shouldBe "on_demand"

        // Triggers
        schema.triggers shouldHaveSize 2
        val trg = schema.triggers["trg_updated"]!!
        trg.table shouldBe "orders"
        trg.event shouldBe TriggerEvent.UPDATE
        trg.timing shouldBe TriggerTiming.BEFORE
        trg.forEach shouldBe TriggerForEach.ROW
        trg.condition shouldBe "OLD.status != NEW.status"
        trg.body shouldNotBe null
        val trgInsert = schema.triggers["trg_insert"]!!
        trgInsert.event shouldBe TriggerEvent.INSERT
        trgInsert.timing shouldBe TriggerTiming.AFTER
        trgInsert.forEach shouldBe TriggerForEach.STATEMENT

        // Sequences
        schema.sequences shouldHaveSize 2
        val seq = schema.sequences["invoice_seq"]!!
        seq.start shouldBe 10000
        seq.increment shouldBe 1
        seq.minValue shouldBe 10000
        seq.maxValue shouldBe 99999999
        seq.cycle shouldBe false
        seq.cache shouldBe 20
        val simpleSeq = schema.sequences["simple_seq"]!!
        simpleSeq.start shouldBe 1
        simpleSeq.increment shouldBe 1
    }

    test("parse schema via Path") {
        val path = java.nio.file.Paths.get(
            YamlSchemaCodecTest::class.java.getResource("/fixtures/schemas/minimal.yaml")!!.toURI()
        )
        val schema = codec.read(path)
        schema.name shouldBe "Minimal Schema"
    }

    test("empty YAML throws exception") {
        val input = "".byteInputStream()
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            codec.read(input)
        }
    }

    test("YAML with missing name throws exception") {
        val input = "schema_format: '1.0'\nversion: '1.0'".byteInputStream()
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            codec.read(input)
        }
    }

    // Invalid fixture tests — each triggers exactly one error code
    listOf(
        "E001" to "E001_table_no_columns.yaml",
        "E002" to "E002_fk_missing_table.yaml",
        "E003" to "E003_fk_missing_column.yaml",
        "E005" to "E005_index_missing_column.yaml",
        "E006" to "E006_enum_empty_values.yaml",
        "E007" to "E007_ref_type_missing.yaml",
        "E008" to "E008_no_primary_key.yaml",
        "E009" to "E009_default_type_mismatch.yaml",
        "E010" to "E010_decimal_invalid_precision.yaml",
        "E011" to "E011_max_length_not_positive.yaml",
        "E012" to "E012_check_unknown_column.yaml",
        "E013" to "E013_enum_both_ref_and_values.yaml",
        "E014" to "E014_char_length_zero.yaml",
        "E015" to "E015_array_empty_element_type.yaml",
        "E016" to "E016_partition_key_missing_column.yaml",
        "E017" to "E017_fk_type_mismatch.yaml",
    ).forEach { (errorCode, file) ->
        test("invalid fixture $file triggers $errorCode") {
            val schema = loadFixture("invalid/$file")
            val result = validator.validate(schema)
            result.errors.any { it.code == errorCode } shouldBe true
        }
    }
})
