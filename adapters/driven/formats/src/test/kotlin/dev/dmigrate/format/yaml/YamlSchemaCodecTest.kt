package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.SchemaValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

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

    test("parse all-types schema covers pre-0.5.5 neutral types (geometry tested separately in spatial.yaml)") {
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
        cols["col_email"]!!.type shouldBe NeutralType.Email
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

    test("parse view dependencies with explicit views list") {
        val yaml = """
            schema_format: "1.0"
            name: "Views"
            version: "1.0.0"
            views:
              summary:
                query: "SELECT * FROM base_view"
                dependencies:
                  tables: [orders]
                  views: [base_view]
                source_dialect: postgresql
              base_view:
                query: "SELECT * FROM orders"
                dependencies:
                  tables: [orders]
                source_dialect: postgresql
        """.trimIndent()

        val schema = codec.read(yaml.byteInputStream())

        schema.views["summary"]!!.dependencies shouldBe DependencyInfo(
            tables = listOf("orders"),
            views = listOf("base_view"),
        )
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

    // ─── Parser-negative codec tests (§C.4) ──────────────────────

    test("duplicate YAML key throws parse error") {
        val yaml = """
        schema_format: "1.0"
        name: "Dup"
        version: "1.0"
        tables:
          t:
            columns:
              id:
                type: identifier
              id:
                type: text
            primary_key: [id]
        """.trimIndent()
        io.kotest.assertions.throwables.shouldThrow<Exception> {
            codec.read(yaml.byteInputStream())
        }
    }

    test("unknown type throws parse error") {
        val yaml = """
        schema_format: "1.0"
        name: "Unknown"
        version: "1.0"
        tables:
          t:
            columns:
              id:
                type: identifier
              bad:
                type: foobar
            primary_key: [id]
        """.trimIndent()
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            codec.read(yaml.byteInputStream())
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
        // E008 is now a warning (0.6.0), tested separately below
        "E009" to "E009_default_type_mismatch.yaml",
        "E010" to "E010_decimal_invalid_precision.yaml",
        "E011" to "E011_max_length_not_positive.yaml",
        "E012" to "E012_check_unknown_column.yaml",
        "E013" to "E013_enum_both_ref_and_values.yaml",
        "E014" to "E014_char_length_zero.yaml",
        "E015" to "E015_array_empty_element_type.yaml",
        "E016" to "E016_partition_key_missing_column.yaml",
        "E017" to "E017_fk_type_mismatch.yaml",
        "E120" to "E120_invalid_geometry_type.yaml",
        "E121" to "E121_srid_zero.yaml",
        "E121" to "E121_srid_negative.yaml",
    ).forEach { (errorCode, file) ->
        test("invalid fixture $file triggers $errorCode") {
            val schema = loadFixture("invalid/$file")
            val result = validator.validate(schema)
            result.errors.any { it.code == errorCode } shouldBe true
        }
    }

    test("invalid fixture E008_no_primary_key.yaml triggers E008 as warning") {
        val schema = loadFixture("invalid/E008_no_primary_key.yaml")
        val result = validator.validate(schema)
        result.isValid shouldBe true
        result.warnings.any { it.code == "E008" } shouldBe true
    }

    // ─── Spatial Phase 1 (0.5.5) ────────────────────────────────

    test("parse spatial schema with geometry_type and srid") {
        val schema = loadFixture("schemas/spatial.yaml")
        schema.name shouldBe "Spatial Schema"
        schema.tables shouldHaveSize 1
        val places = schema.tables["places"]!!
        places.columns shouldHaveSize 5

        val location = places.columns["location"]!!
        val locType = location.type as NeutralType.Geometry
        locType.geometryType.schemaName shouldBe "point"
        locType.srid shouldBe 4326

        val area = places.columns["area"]!!
        val areaType = area.type as NeutralType.Geometry
        areaType.geometryType.schemaName shouldBe "polygon"
        areaType.srid shouldBe null

        val shape = places.columns["shape"]!!
        val shapeType = shape.type as NeutralType.Geometry
        shapeType.geometryType shouldBe GeometryType.GEOMETRY
        shapeType.srid shouldBe null
    }

    test("spatial schema passes validation") {
        val schema = loadFixture("schemas/spatial.yaml")
        val result = validator.validate(schema)
        result.isValid shouldBe true
    }

    test("unknown geometry_type is read losslessly and triggers E120 in validator") {
        val schema = loadFixture("invalid/E120_invalid_geometry_type.yaml")
        val loc = schema.tables["places"]!!.columns["location"]!!
        val geo = loc.type as NeutralType.Geometry
        geo.geometryType.schemaName shouldBe "circle"
        geo.geometryType.isKnown() shouldBe false

        val result = validator.validate(schema)
        result.errors.any { it.code == "E120" } shouldBe true
    }

    test("srid zero is read and triggers E121 in validator") {
        val schema = loadFixture("invalid/E121_srid_zero.yaml")
        val loc = schema.tables["places"]!!.columns["location"]!!
        val geo = loc.type as NeutralType.Geometry
        geo.srid shouldBe 0

        val result = validator.validate(schema)
        result.errors.any { it.code == "E121" } shouldBe true
    }

    test("negative srid is read and triggers E121 in validator") {
        val schema = loadFixture("invalid/E121_srid_negative.yaml")
        val loc = schema.tables["places"]!!.columns["location"]!!
        val geo = loc.type as NeutralType.Geometry
        geo.srid shouldBe -1

        val result = validator.validate(schema)
        result.errors.any { it.code == "E121" } shouldBe true
    }

    test("JSON smoke: spatial schema via same codec read path") {
        val json = """
        {
          "schema_format": "1.0",
          "name": "JSON Spatial",
          "version": "1.0.0",
          "tables": {
            "geo": {
              "columns": {
                "id": {"type": "identifier", "auto_increment": true},
                "loc": {"type": "geometry", "geometry_type": "point", "srid": 4326}
              },
              "primary_key": ["id"]
            }
          }
        }
        """.trimIndent()
        val schema = codec.read(json.byteInputStream())
        schema.name shouldBe "JSON Spatial"
        val loc = schema.tables["geo"]!!.columns["loc"]!!.type as NeutralType.Geometry
        loc.geometryType.schemaName shouldBe "point"
        loc.srid shouldBe 4326
    }

    // ─── Table Metadata (0.6.0 Phase B) ─────────────────────────

    test("parse table metadata with engine and without_rowid") {
        val schema = loadFixture("schemas/table-metadata.yaml")
        schema.name shouldBe "Table Metadata Schema"
        schema.tables shouldHaveSize 4

        val innodb = schema.tables["innodb_table"]!!
        innodb.metadata shouldNotBe null
        innodb.metadata!!.engine shouldBe "InnoDB"
        innodb.metadata!!.withoutRowid shouldBe false

        val myisam = schema.tables["myisam_table"]!!
        myisam.metadata!!.engine shouldBe "MyISAM"

        val rowid = schema.tables["rowid_table"]!!
        rowid.metadata!!.withoutRowid shouldBe true
        rowid.metadata!!.engine shouldBe null

        val plain = schema.tables["plain_table"]!!
        plain.metadata shouldBe null
    }

    test("table metadata round-trips through comparator") {
        val schema = loadFixture("schemas/table-metadata.yaml")
        val comparator = dev.dmigrate.core.diff.SchemaComparator()

        // Self-compare: identical
        val selfDiff = comparator.compare(schema, schema)
        selfDiff.isEmpty() shouldBe true

        // Modify engine: should detect change
        val modified = schema.copy(
            tables = schema.tables.mapValues { (name, table) ->
                if (name == "innodb_table") {
                    table.copy(metadata = dev.dmigrate.core.model.TableMetadata(engine = "MyISAM"))
                } else table
            }
        )
        val diff = comparator.compare(schema, modified)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].name shouldBe "innodb_table"
        diff.tablesChanged[0].metadata shouldNotBe null
    }

    // ─── Canonical Object Keys (0.6.0 Phase B) ─────────────────

    test("canonical routine keys are preserved as map keys through codec") {
        val schema = loadFixture("schemas/canonical-keys.yaml")
        schema.name shouldBe "Canonical Key Schema"

        // Overloaded functions: both keys preserved
        schema.functions shouldHaveSize 2
        schema.functions.keys shouldBe setOf("calc(in:integer)", "calc(in:integer,in:integer)")

        val calcOne = schema.functions["calc(in:integer)"]!!
        calcOne.parameters shouldHaveSize 1
        calcOne.body shouldBe "RETURN x * 2;"

        val calcTwo = schema.functions["calc(in:integer,in:integer)"]!!
        calcTwo.parameters shouldHaveSize 2
        calcTwo.body shouldBe "RETURN x + y;"

        // Procedure with canonical key
        schema.procedures shouldHaveSize 1
        schema.procedures.keys.first() shouldBe "reset_stats(in:integer)"
    }

    test("canonical trigger keys are preserved as map keys through codec") {
        val schema = loadFixture("schemas/canonical-keys.yaml")

        // Same-named triggers on different tables: both keys preserved
        schema.triggers shouldHaveSize 2
        schema.triggers.keys shouldBe setOf("users::audit_insert", "orders::audit_insert")

        val usersTrigger = schema.triggers["users::audit_insert"]!!
        usersTrigger.table shouldBe "users"

        val ordersTrigger = schema.triggers["orders::audit_insert"]!!
        ordersTrigger.table shouldBe "orders"
    }

    test("canonical keys survive comparator round-trip") {
        val schema = loadFixture("schemas/canonical-keys.yaml")
        val comparator = dev.dmigrate.core.diff.SchemaComparator()

        // Self-compare: identical
        val selfDiff = comparator.compare(schema, schema)
        selfDiff.isEmpty() shouldBe true

        // Modify one overload: only that one shows as changed
        val modified = schema.copy(
            functions = schema.functions.mapValues { (key, fn) ->
                if (key == "calc(in:integer)") fn.copy(body = "RETURN x * 3;") else fn
            }
        )
        val diff = comparator.compare(schema, modified)
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].name shouldBe "calc(in:integer)"
        diff.functionsAdded shouldHaveSize 0
        diff.functionsRemoved shouldHaveSize 0
    }

    // ─── dependencies.functions (0.9.2 AP 6.3 Step A) ────────────

    test("parse view with dependencies.functions") {
        val yaml = """
            schema_format: "1.0"
            name: "FuncDeps"
            version: "1.0.0"
            views:
              computed:
                query: "SELECT calc_total(id) FROM orders"
                dependencies:
                  tables: [orders]
                  functions: [calc_total]
                source_dialect: postgresql
        """.trimIndent()

        val schema = codec.read(yaml.byteInputStream())
        schema.views["computed"]!!.dependencies shouldBe DependencyInfo(
            tables = listOf("orders"),
            functions = listOf("calc_total"),
        )
    }

    test("parse view without dependencies.functions defaults to emptyList") {
        val yaml = """
            schema_format: "1.0"
            name: "NoDeps"
            version: "1.0.0"
            views:
              simple:
                query: "SELECT * FROM orders"
                dependencies:
                  tables: [orders]
                source_dialect: postgresql
        """.trimIndent()

        val schema = codec.read(yaml.byteInputStream())
        schema.views["simple"]!!.dependencies!!.functions shouldBe emptyList()
    }

    test("dependencies.functions round-trips through write and read") {
        val yaml = """
            schema_format: "1.0"
            name: "RoundTrip"
            version: "1.0.0"
            views:
              v1:
                query: "SELECT f1(id) FROM t"
                dependencies:
                  tables: [t]
                  functions: [f1, f2]
                source_dialect: postgresql
        """.trimIndent()

        val schema = codec.read(yaml.byteInputStream())
        val out = java.io.ByteArrayOutputStream()
        codec.write(out, schema)
        val reparsed = codec.read(out.toByteArray().inputStream())
        reparsed.views["v1"]!!.dependencies!!.functions shouldBe listOf("f1", "f2")
    }

    // ─── SequenceNextVal codec (0.9.3 AP 6.3) ───────────────────

    test("round-trip for default.sequence_nextval") {
        val yaml = """
            schema_format: "1.0"
            name: "SeqTest"
            version: "1.0.0"
            encoding: "utf-8"
            sequences:
              invoice_seq:
                start: 1000
            tables:
              invoices:
                columns:
                  id:
                    type: integer
                    default:
                      sequence_nextval: invoice_seq
                primary_key: [id]
        """.trimIndent()
        val schema = codec.read(yaml.byteInputStream())
        schema.tables["invoices"]!!.columns["id"]!!.default shouldBe
            DefaultValue.SequenceNextVal("invoice_seq")

        // Round-trip: write and re-read
        val out = java.io.ByteArrayOutputStream()
        codec.write(out, schema)
        val reparsed = codec.read(out.toByteArray().inputStream())
        reparsed.tables["invoices"]!!.columns["id"]!!.default shouldBe
            DefaultValue.SequenceNextVal("invoice_seq")
    }

    test("legacy text default nextval is parsed as FunctionCall for validator") {
        val yaml = """
            schema_format: "1.0"
            name: "LegacyTest"
            version: "1.0.0"
            encoding: "utf-8"
            tables:
              t:
                columns:
                  id:
                    type: integer
                    default: "nextval('my_seq')"
                primary_key: [id]
        """.trimIndent()
        val schema = codec.read(yaml.byteInputStream())
        val default = schema.tables["t"]!!.columns["id"]!!.default
        default shouldBe DefaultValue.FunctionCall("nextval('my_seq')")
    }

    test("map default with key 'nextval' is parsed as FunctionCall for E122 in validator") {
        val yaml = """
            schema_format: "1.0"
            name: "BadMap"
            version: "1.0.0"
            encoding: "utf-8"
            tables:
              t:
                columns:
                  id:
                    type: integer
                    default:
                      nextval: my_seq
                primary_key: [id]
        """.trimIndent()
        val schema = codec.read(yaml.byteInputStream())
        val default = schema.tables["t"]!!.columns["id"]!!.default
        default shouldBe DefaultValue.FunctionCall("nextval('my_seq')")
        // Validator will emit E122 for this FunctionCall
        val result = validator.validate(schema)
        result.errors.any { it.code == "E122" } shouldBe true
    }

    test("map default with unknown key throws structured error") {
        val yaml = """
            schema_format: "1.0"
            name: "UnknownKey"
            version: "1.0.0"
            encoding: "utf-8"
            tables:
              t:
                columns:
                  id:
                    type: integer
                    default:
                      unknown_form: value
                primary_key: [id]
        """.trimIndent()
        io.kotest.assertions.throwables.shouldThrow<Exception> {
            codec.read(yaml.byteInputStream())
        }
    }
})
