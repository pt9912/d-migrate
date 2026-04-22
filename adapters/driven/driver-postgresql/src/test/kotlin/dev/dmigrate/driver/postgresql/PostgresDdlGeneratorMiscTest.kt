package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.SpatialProfile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PostgresDdlGeneratorMiscTest : FunSpec({

    val generator = PostgresDdlGenerator()

    fun schema(
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

    fun table(
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

    fun col(
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

    test("BTREE index omits USING clause") {
        val s = schema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf("name" to col(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(name = "idx_products_name", columns = listOf("name"), type = IndexType.BTREE)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE INDEX \"idx_products_name\" ON \"products\" (\"name\");"
        ddl shouldNotContain "USING"
    }

    test("HASH index includes USING HASH") {
        val s = schema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf("code" to col(NeutralType.Text(maxLength = 20))),
                    indices = listOf(
                        IndexDefinition(name = "idx_products_code", columns = listOf("code"), type = IndexType.HASH)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE INDEX \"idx_products_code\" ON \"products\" USING HASH (\"code\");"
    }

    test("unique index generates CREATE UNIQUE INDEX") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf("email" to col(NeutralType.Email)),
                    indices = listOf(
                        IndexDefinition(name = "idx_users_email", columns = listOf("email"), unique = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE UNIQUE INDEX \"idx_users_email\" ON \"users\" (\"email\");"
    }

    test("index name is auto-generated when not provided") {
        val s = schema(
            tables = mapOf(
                "logs" to table(
                    columns = mapOf("level" to col(NeutralType.Text())),
                    indices = listOf(
                        IndexDefinition(columns = listOf("level"))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE INDEX \"idx_logs_level\" ON \"logs\" (\"level\");"
    }

    test("multiple columns index") {
        val s = schema(
            tables = mapOf(
                "orders" to table(
                    columns = mapOf(
                        "customer_id" to col(NeutralType.Integer),
                        "order_date" to col(NeutralType.Date)
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_orders_cust_date",
                            columns = listOf("customer_id", "order_date")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE INDEX \"idx_orders_cust_date\" ON \"orders\" (\"customer_id\", \"order_date\");"
    }

    test("view generates CREATE OR REPLACE VIEW") {
        val s = schema(
            views = mapOf(
                "active_users" to ViewDefinition(
                    query = "SELECT * FROM users WHERE active = TRUE"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE OR REPLACE VIEW \"active_users\" AS"
        ddl shouldContain "SELECT * FROM users WHERE active = TRUE"
    }

    test("materialized view generates CREATE MATERIALIZED VIEW") {
        val s = schema(
            views = mapOf(
                "sales_summary" to ViewDefinition(
                    materialized = true,
                    query = "SELECT product_id, SUM(amount) FROM sales GROUP BY product_id"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE MATERIALIZED VIEW \"sales_summary\" AS"
        ddl shouldContain "SELECT product_id, SUM(amount) FROM sales GROUP BY product_id"
    }

    test("view with incompatible source_dialect is transformed best-effort and warns with W111") {
        val s = schema(
            views = mapOf(
                "mysql_view" to ViewDefinition(
                    query = "SELECT IFNULL(x, 0) FROM t",
                    sourceDialect = "mysql"
                )
            )
        )
        val result = generator.generate(s)
        val rendered = result.render()
        rendered shouldContain "CREATE OR REPLACE VIEW \"mysql_view\" AS"
        rendered shouldContain "SELECT IFNULL(x, 0) FROM t;"
        rendered shouldContain "W111"
        result.notes.any { it.code == "W111" && it.objectName == "view_query" } shouldBe true
        result.skippedObjects.any { it.name == "mysql_view" } shouldBe false
    }

    test("view without query is skipped") {
        val s = schema(views = mapOf("no_query_view" to ViewDefinition()))
        val result = generator.generate(s)
        result.skippedObjects.any { it.name == "no_query_view" } shouldBe true
    }

    test("rollback generates DROP statements in reverse order") {
        val s = schema(
            customTypes = mapOf(
                "status_enum" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("a", "b")
                )
            ),
            sequences = mapOf(
                "my_seq" to SequenceDefinition(start = 1, increment = 1)
            ),
            tables = mapOf(
                "items" to table(
                    columns = mapOf("id" to col(NeutralType.Integer)),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(name = "idx_items_id", columns = listOf("id"))
                    )
                )
            ),
            views = mapOf(
                "all_items" to ViewDefinition(query = "SELECT * FROM items")
            )
        )
        val rollback = generator.generateRollback(s)
        val rendered = rollback.render()

        rendered shouldContain "DROP VIEW IF EXISTS"
        rendered shouldContain "DROP INDEX IF EXISTS"
        rendered shouldContain "DROP TABLE IF EXISTS"
        rendered shouldContain "DROP SEQUENCE IF EXISTS"
        rendered shouldContain "DROP TYPE IF EXISTS"

        val dropView = rendered.indexOf("DROP VIEW")
        val dropIndex = rendered.indexOf("DROP INDEX")
        val dropTable = rendered.indexOf("DROP TABLE")
        val dropSeq = rendered.indexOf("DROP SEQUENCE")
        val dropType = rendered.indexOf("DROP TYPE")

        (dropView < dropIndex) shouldBe true
        (dropIndex < dropTable) shouldBe true
        (dropTable < dropSeq) shouldBe true
        (dropSeq < dropType) shouldBe true
    }

    test("header contains schema name and dialect") {
        val s = schema(name = "my_app_db", version = "2.1")
        val ddl = generator.generate(s).render()
        ddl shouldContain "d-migrate"
        ddl shouldContain "my_app_db"
        ddl shouldContain "2.1"
        ddl shouldContain "postgresql"
    }

    test("dialect is POSTGRESQL") {
        generator.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

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
})
