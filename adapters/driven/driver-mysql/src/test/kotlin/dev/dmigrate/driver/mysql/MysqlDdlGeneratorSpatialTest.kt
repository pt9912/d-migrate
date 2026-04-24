package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlDdlGeneratorSpatialTest : FunSpec({

    val generator = MysqlDdlGenerator()

    test("geometry point column produces POINT") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        val ddl = result.render()
        ddl shouldContain "POINT"
        ddl shouldContain "SRID"
    }

    listOf(
        "polygon" to "POLYGON",
        "multipolygon" to "MULTIPOLYGON",
        "geometry" to "GEOMETRY",
    ).forEach { (geometryType, expectedSqlType) ->
        test("geometry $geometryType column produces $expectedSqlType") {
            val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
                "shapes" to TableDefinition(columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                    "shape" to ColumnDefinition(type = NeutralType.Geometry(GeometryType(geometryType))),
                ), primaryKey = listOf("id"))
            ))

            val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
            val ddl = result.render()

            ddl shouldContain expectedSqlType
            result.notes.none { it.code == "W120" } shouldBe true
        }
    }

    test("geometry with srid emits W120 warning") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        result.notes.any { it.code == "W120" } shouldBe true
    }

    test("geometry without srid produces no W120") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        result.notes.none { it.code == "W120" } shouldBe true
    }

    test("spatial rollback under native profile drops the table without SQLite-specific statements") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"), srid = 4326)),
                "area" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("polygon"))),
            ), primaryKey = listOf("id"))
        ))

        val ddl = generator.generateRollback(schema, DdlGenerationOptions(SpatialProfile.NATIVE)).render()

        ddl shouldContain "DROP TABLE IF EXISTS `places`;"
        ddl shouldNotContain "AddGeometryColumn"
        ddl shouldNotContain "DiscardGeometryColumn"
    }
})
