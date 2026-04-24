package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SpatialProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

// ── Helpers ────────────────────────────────────────────────

private fun schema(
    name: String = "test_schema",
    version: String = "1.0",
    tables: Map<String, TableDefinition> = emptyMap(),
    customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
    sequences: Map<String, SequenceDefinition> = emptyMap(),
    views: Map<String, ViewDefinition> = emptyMap(),
    functions: Map<String, FunctionDefinition> = emptyMap(),
    procedures: Map<String, ProcedureDefinition> = emptyMap(),
    triggers: Map<String, TriggerDefinition> = emptyMap()
) = SchemaDefinition(
    name = name,
    version = version,
    tables = tables,
    customTypes = customTypes,
    sequences = sequences,
    views = views,
    functions = functions,
    procedures = procedures,
    triggers = triggers
)

private fun table(
    columns: Map<String, ColumnDefinition>,
    primaryKey: List<String> = emptyList(),
    indices: List<IndexDefinition> = emptyList(),
    constraints: List<ConstraintDefinition> = emptyList(),
    partitioning: PartitionConfig? = null
) = TableDefinition(
    columns = columns,
    primaryKey = primaryKey,
    indices = indices,
    constraints = constraints,
    partitioning = partitioning
)

private fun col(
    type: NeutralType,
    required: Boolean = false,
    unique: Boolean = false,
    default: DefaultValue? = null,
    references: ReferenceDefinition? = null
) = ColumnDefinition(
    type = type,
    required = required,
    unique = unique,
    default = default,
    references = references
)

// ── Tests ──────────────────────────────────────────────────

class PostgresDdlGeneratorTestPart3 : FunSpec({

    val generator = PostgresDdlGenerator()

    // 1. Simple table with columns and PK


    test("array with boolean element type") {
        val s = schema(tables = mapOf("t" to table(
            columns = mapOf("flags" to col(NeutralType.Array("boolean")))
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "BOOLEAN[]"
    }

    test("array with uuid element type") {
        val s = schema(tables = mapOf("t" to table(
            columns = mapOf("refs" to col(NeutralType.Array("uuid")))
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "UUID[]"
    }

    test("uuid json binary and array retain their PostgreSQL mappings") {
        val s = schema(
            tables = mapOf(
                "typed" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(true)),
                        "external_id" to col(NeutralType.Uuid),
                        "payload" to col(NeutralType.Json),
                        "raw_data" to col(NeutralType.Binary),
                        "tags" to col(NeutralType.Array("text")),
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val ddl = generator.generate(s).render()

        ddl shouldContain "\"external_id\" UUID"
        ddl shouldContain "\"payload\" JSONB"
        ddl shouldContain "\"raw_data\" BYTEA"
        ddl shouldContain "\"tags\" TEXT[]"
    }

    // ─── Spatial Phase 1 ────────────────────────────────────

    test("geometry column with postgis profile produces geometry(Point, 4326)") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.POSTGIS))
        val ddl = result.render()
        ddl shouldContain "geometry(Point, 4326)"
    }

    test("geometry column without srid uses 0") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "shape" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.POSTGIS))
        result.render() shouldContain "geometry(Geometry, 0)"
    }

    test("profile none blocks table with geometry columns") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"))),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NONE))
        result.notes.any { it.code == "E052" && it.objectName == "places" } shouldBe true
        result.skippedObjects.any { it.code == "E052" } shouldBe true
        result.render() shouldNotContain "CREATE TABLE"
    }

    test("geometry multipolygon with postgis profile") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "area" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("multipolygon"), srid = 3857)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.POSTGIS))
        result.render() shouldContain "geometry(MultiPolygon, 3857)"
    }

    test("geometry linestring with postgis profile") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "path" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("linestring"))),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.POSTGIS))
        result.render() shouldContain "geometry(LineString, 0)"
    }

    test("postgis rollback for geometry table is DROP TABLE") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generateRollback(schema, DdlGenerationOptions(SpatialProfile.POSTGIS))
        result.render() shouldContain "DROP TABLE"
    }

    test("profile none does not generate tables without geometry") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "normal" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
            ), primaryKey = listOf("id")),
            "spatial" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id")),
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NONE))
        result.render() shouldContain "CREATE TABLE \"normal\""
        result.render() shouldNotContain "CREATE TABLE \"spatial\""
        result.skippedObjects.size shouldBe 1
    }

    test("postgis profile emits PostGIS info note") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "g" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.POSTGIS))
        result.notes.any { it.code == "I001" } shouldBe true
    }

    // ── security: malicious identifiers are quoted ─

    test("malicious table and column names are properly quoted in DDL") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "Robert'; DROP TABLE users; --" to TableDefinition(columns = mapOf(
                "col\"inject" to ColumnDefinition(type = NeutralType.Text()),
            ))
        ))
        val rendered = generator.generate(schema).render()
        // Table name is safely quoted — injection payload neutralized
        rendered shouldContain "\"Robert'; DROP TABLE users; --\""
        // Embedded double-quote is escaped as ""
        rendered shouldContain "\"col\"\"inject\""
    }
})
