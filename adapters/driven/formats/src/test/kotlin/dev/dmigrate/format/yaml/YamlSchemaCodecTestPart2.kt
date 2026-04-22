package dev.dmigrate.format.yaml

import dev.dmigrate.core.model.*
import dev.dmigrate.core.validation.SchemaValidator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class YamlSchemaCodecTestPart2 : FunSpec({

    val codec = YamlSchemaCodec()
    val validator = SchemaValidator()

    fun loadFixture(path: String) =
        codec.read(YamlSchemaCodecTest::class.java.getResourceAsStream("/fixtures/$path")!!)


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

    test("map default with key 'nextval' throws parse error with migration hint") {
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
        val ex = io.kotest.assertions.throwables.shouldThrow<Exception> {
            codec.read(yaml.byteInputStream())
        }
        ex.message shouldContain "sequence_nextval"
        ex.message shouldContain "my_seq"
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

    test("array default throws structured error") {
        val yaml = """
            schema_format: "1.0"
            name: "ArrayDefault"
            version: "1.0.0"
            encoding: "utf-8"
            tables:
              t:
                columns:
                  tags:
                    type: text
                    default:
                      - a
                      - b
                primary_key: [tags]
        """.trimIndent()
        val ex = io.kotest.assertions.throwables.shouldThrow<Exception> {
            codec.read(yaml.byteInputStream())
        }
        ex.message shouldContain "Unsupported default node type"
    }
})
