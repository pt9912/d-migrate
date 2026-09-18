package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SqliteDdlGeneratorSpatialTest : FunSpec({

    val generator = SqliteDdlGenerator()

    test("spatialite profile produces AddGeometryColumn after CREATE TABLE") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))
        val ddl = result.render()
        ddl shouldContain "CREATE TABLE"
        ddl shouldContain "AddGeometryColumn('places', 'location', 4326, 'POINT', 'XY')"
        ddl shouldNotContain "geometry"
    }

    test("spatialite rollback produces DiscardGeometryColumn") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"))),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generateRollback(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))
        result.render() shouldContain "DiscardGeometryColumn"
    }

    test("profile none blocks table with geometry columns") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NONE))
        result.notes.any { it.code == "E052" && it.objectName == "places" } shouldBe true
        result.skippedObjects.any { it.code == "E052" } shouldBe true
        result.render() shouldNotContain "CREATE TABLE \"places\""
        result.render() shouldNotContain "AddGeometryColumn('places'"
    }

    test("spatialite rollback with multiple geometry columns: DiscardGeometryColumn before DROP TABLE") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "geo" to TableDefinition(columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"), srid = 4326)),
                "area" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("polygon"))),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generateRollback(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))
        val ddl = result.render()
        val discardLoc = ddl.indexOf("DiscardGeometryColumn('geo', 'loc')")
        val discardArea = ddl.indexOf("DiscardGeometryColumn('geo', 'area')")
        val dropTable = ddl.indexOf("DROP TABLE")
        (discardLoc >= 0) shouldBe true
        (discardArea >= 0) shouldBe true
        (dropTable >= 0) shouldBe true
        (discardLoc < dropTable) shouldBe true
        (discardArea < dropTable) shouldBe true
    }

    // P7: `required` blockt **nicht** mehr — die Ausloeser, die bleiben, sind
    // `unique`, `default`, ein Fremdschluessel, der Primaerschluessel und eine
    // tabellenweite Einschraenkung. Hier steht `unique`.
    test("spatialite metadata blocking adds SkippedObject") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(), unique = true),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))
        result.skippedObjects.any { it.code == "E052" && it.name == "t" } shouldBe true
    }

    test("spatialite blocks table when geometry column has a DEFAULT") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(
                    type = NeutralType.Geometry(),
                    default = DefaultValue.StringLiteral("x"),
                ),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))
        result.notes.any { it.code == "E052" } shouldBe true
    }

    // P7: `required` entsteht nativ ueber das sechste Argument von
    // `AddGeometryColumn` (gemessen an SpatiaLite 5.1.0: die Spalte wird
    // `"loc" POINT NOT NULL DEFAULT ''`, und eine Zeile ohne Geometrie wird
    // abgewiesen). Bis hierher fiel dafuer die **ganze** Tabelle weg.
    test("P7: eine required-Geometriespalte bekommt das sechste Argument, ohne E052") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(
                    type = NeutralType.Geometry(GeometryType("point"), srid = 4326),
                    required = true,
                ),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))

        result.notes.none { it.code == "E052" } shouldBe true
        result.skippedObjects.none { it.code == "E052" } shouldBe true
        result.render() shouldContain "AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY', 1)"
    }

    // Gegenprobe: eine nullbare Spalte behaelt die fuenfstellige Form — sonst
    // bewegte sich jedes bestehende DDL-Golden.
    test("P7: eine nullbare Geometriespalte behaelt die fuenfstellige Form") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val ddl = generator.generate(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE)).render()

        ddl shouldContain "AddGeometryColumn('places', 'loc', 4326, 'POINT', 'XY');"
        ddl shouldNotContain "'XY', 1"
    }

    // N4: eine tabellenweite Einschraenkung auf der Geometriespalte blockt
    // weiter — in beiden Pfaden dieselbe Regel.
    test("P7: eine tabellenweite Einschraenkung auf der Geometriespalte blockt weiter") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                    "loc" to ColumnDefinition(type = NeutralType.Geometry(), required = true),
                ),
                primaryKey = listOf("id"),
                constraints = listOf(
                    ConstraintDefinition(
                        name = "uq_loc",
                        type = ConstraintType.UNIQUE,
                        columns = listOf("loc"),
                    ),
                ),
            )
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.SPATIALITE))
        result.notes.any { it.code == "E052" } shouldBe true
    }
})
