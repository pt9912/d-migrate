package dev.dmigrate.core.diff

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull

class SchemaComparatorTest : FunSpec({

    val comparator = SchemaComparator()

    // --- Builder helpers ---

    fun schema(
        name: String = "Test",
        version: String = "1.0",
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = name,
        version = version,
        tables = tables,
        customTypes = customTypes,
        views = views,
    )

    fun table(
        columns: Map<String, ColumnDefinition> = emptyMap(),
        primaryKey: List<String> = emptyList(),
        indices: List<IndexDefinition> = emptyList(),
        constraints: List<ConstraintDefinition> = emptyList(),
    ) = TableDefinition(
        columns = columns,
        primaryKey = primaryKey,
        indices = indices,
        constraints = constraints,
    )

    fun col(
        type: NeutralType = NeutralType.Text(),
        required: Boolean = false,
        unique: Boolean = false,
        default: DefaultValue? = null,
        references: ReferenceDefinition? = null,
    ) = ColumnDefinition(type = type, required = required, unique = unique,
        default = default, references = references)

    fun enumType(vararg values: String) =
        CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = values.toList())

    fun view(
        materialized: Boolean = false,
        refresh: String? = null,
        query: String? = null,
        sourceDialect: String? = null,
    ) = ViewDefinition(
        materialized = materialized,
        refresh = refresh,
        query = query,
        sourceDialect = sourceDialect,
    )

    // ===========================================
    // 8.1 Pflichtfaelle
    // ===========================================

    test("identical schemas produce empty diff") {
        val s = schema(tables = mapOf(
            "users" to table(
                columns = mapOf("id" to col(NeutralType.Identifier(true)), "name" to col(NeutralType.Text(100))),
                primaryKey = listOf("id"),
            )
        ))
        val diff = comparator.compare(s, s)
        diff.isEmpty() shouldBe true
    }

    test("table added") {
        val left = schema()
        val right = schema(tables = mapOf("users" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesAdded shouldHaveSize 1
        diff.tablesAdded[0].name shouldBe "users"
        diff.tablesRemoved.shouldBeEmpty()
    }

    test("table removed") {
        val left = schema(tables = mapOf("users" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))
        val right = schema()

        val diff = comparator.compare(left, right)
        diff.tablesRemoved shouldHaveSize 1
        diff.tablesRemoved[0].name shouldBe "users"
        diff.tablesAdded.shouldBeEmpty()
    }

    test("column added") {
        val left = schema(tables = mapOf("users" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))
        val right = schema(tables = mapOf("users" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "email" to col(NeutralType.Email),
            ),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].columnsAdded shouldHaveSize 1
        diff.tablesChanged[0].columnsAdded.keys.first() shouldBe "email"
    }

    test("column removed") {
        val left = schema(tables = mapOf("users" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "email" to col(NeutralType.Email),
            ),
        )))
        val right = schema(tables = mapOf("users" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].columnsRemoved shouldHaveSize 1
        diff.tablesChanged[0].columnsRemoved.keys.first() shouldBe "email"
    }

    test("column changed - type") {
        val left = schema(tables = mapOf("users" to table(
            columns = mapOf("name" to col(NeutralType.Text(100))),
        )))
        val right = schema(tables = mapOf("users" to table(
            columns = mapOf("name" to col(NeutralType.Text(200))),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        val colDiff = diff.tablesChanged[0].columnsChanged[0]
        colDiff.name shouldBe "name"
        colDiff.type.shouldNotBeNull()
        colDiff.type!!.before shouldBe NeutralType.Text(100)
        colDiff.type!!.after shouldBe NeutralType.Text(200)
    }

    test("column changed - required") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col(required = false)),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col(required = true)),
        )))

        val diff = comparator.compare(left, right)
        val colDiff = diff.tablesChanged[0].columnsChanged[0]
        colDiff.required.shouldNotBeNull()
        colDiff.required!!.before shouldBe false
        colDiff.required!!.after shouldBe true
    }

    test("column changed - default") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col(default = null)),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col(default = DefaultValue.StringLiteral("hello"))),
        )))

        val diff = comparator.compare(left, right)
        val colDiff = diff.tablesChanged[0].columnsChanged[0]
        colDiff.default.shouldNotBeNull()
        colDiff.default!!.before shouldBe null
        colDiff.default!!.after shouldBe DefaultValue.StringLiteral("hello")
    }

    test("primary key change") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "tenant_id" to col(NeutralType.Identifier()),
            ),
            primaryKey = listOf("id"),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "tenant_id" to col(NeutralType.Identifier()),
            ),
            primaryKey = listOf("tenant_id", "id"),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        val pk = diff.tablesChanged[0].primaryKey
        pk.shouldNotBeNull()
        pk.before shouldBe listOf("id")
        pk.after shouldBe listOf("tenant_id", "id")
    }

    test("primary key order matters") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "a" to col(NeutralType.Identifier()),
                "b" to col(NeutralType.Identifier()),
            ),
            primaryKey = listOf("a", "b"),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "a" to col(NeutralType.Identifier()),
                "b" to col(NeutralType.Identifier()),
            ),
            primaryKey = listOf("b", "a"),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].primaryKey.shouldNotBeNull()
    }

    test("enum custom type changed") {
        val left = schema(customTypes = mapOf(
            "status" to enumType("active", "inactive"),
        ))
        val right = schema(customTypes = mapOf(
            "status" to enumType("active", "inactive", "archived"),
        ))

        val diff = comparator.compare(left, right)
        diff.enumTypesChanged shouldHaveSize 1
        diff.enumTypesChanged[0].name shouldBe "status"
        diff.enumTypesChanged[0].values.before shouldBe listOf("active", "inactive")
        diff.enumTypesChanged[0].values.after shouldBe listOf("active", "inactive", "archived")
    }

    test("enum custom type added and removed") {
        val left = schema(customTypes = mapOf("old" to enumType("a", "b")))
        val right = schema(customTypes = mapOf("new" to enumType("x", "y")))

        val diff = comparator.compare(left, right)
        diff.enumTypesAdded shouldHaveSize 1
        diff.enumTypesAdded[0].name shouldBe "new"
        diff.enumTypesRemoved shouldHaveSize 1
        diff.enumTypesRemoved[0].name shouldBe "old"
    }

    test("enum value order matters") {
        val left = schema(customTypes = mapOf("s" to enumType("a", "b")))
        val right = schema(customTypes = mapOf("s" to enumType("b", "a")))

        val diff = comparator.compare(left, right)
        diff.enumTypesChanged shouldHaveSize 1
    }

    test("column-based FK change via references") {
        val left = schema(tables = mapOf("orders" to table(
            columns = mapOf("customer_id" to col(
                NeutralType.Integer,
                references = ReferenceDefinition("customers", "id"),
            )),
        )))
        val right = schema(tables = mapOf("orders" to table(
            columns = mapOf("customer_id" to col(
                NeutralType.Integer,
                references = ReferenceDefinition("clients", "id"),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].constraintsChanged shouldHaveSize 1
    }

    test("constraint UNIQUE changed") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col()),
            constraints = listOf(ConstraintDefinition(
                name = "uq_multi", type = ConstraintType.UNIQUE,
                columns = listOf("a", "b"),
            )),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col()),
            constraints = listOf(ConstraintDefinition(
                name = "uq_multi", type = ConstraintType.UNIQUE,
                columns = listOf("a", "b", "c"),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].constraintsChanged shouldHaveSize 1
    }

    test("semantically equivalent single-column UNIQUE produces no diff") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("email" to col(unique = true)),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("email" to col(unique = false)),
            constraints = listOf(ConstraintDefinition(
                name = "uq_email", type = ConstraintType.UNIQUE,
                columns = listOf("email"),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("semantically equivalent single-column FK produces no diff") {
        val left = schema(tables = mapOf("orders" to table(
            columns = mapOf("customer_id" to col(
                NeutralType.Integer,
                references = ReferenceDefinition("customers", "id",
                    onDelete = ReferentialAction.CASCADE),
            )),
        )))
        val right = schema(tables = mapOf("orders" to table(
            columns = mapOf("customer_id" to col(NeutralType.Integer)),
            constraints = listOf(ConstraintDefinition(
                name = "fk_customer", type = ConstraintType.FOREIGN_KEY,
                columns = listOf("customer_id"),
                references = ConstraintReferenceDefinition(
                    table = "customers",
                    columns = listOf("id"),
                    onDelete = ReferentialAction.CASCADE,
                ),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("index changed") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col()),
            indices = listOf(IndexDefinition(name = "idx_c", columns = listOf("c"),
                type = IndexType.BTREE)),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col()),
            indices = listOf(IndexDefinition(name = "idx_c", columns = listOf("c"),
                type = IndexType.HASH)),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].indicesChanged shouldHaveSize 1
        diff.tablesChanged[0].indicesChanged[0].before.type shouldBe IndexType.BTREE
        diff.tablesChanged[0].indicesChanged[0].after.type shouldBe IndexType.HASH
    }

    test("index added and removed") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col()),
            indices = listOf(IndexDefinition(name = "idx_a", columns = listOf("a"))),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col()),
            indices = listOf(IndexDefinition(name = "idx_b", columns = listOf("b"))),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].indicesAdded shouldHaveSize 1
        diff.tablesChanged[0].indicesAdded[0].name shouldBe "idx_b"
        diff.tablesChanged[0].indicesRemoved shouldHaveSize 1
        diff.tablesChanged[0].indicesRemoved[0].name shouldBe "idx_a"
    }

    test("view changed") {
        val left = schema(views = mapOf("v" to view(query = "SELECT 1")))
        val right = schema(views = mapOf("v" to view(query = "SELECT 2")))

        val diff = comparator.compare(left, right)
        diff.viewsChanged shouldHaveSize 1
        diff.viewsChanged[0].name shouldBe "v"
        diff.viewsChanged[0].query.shouldNotBeNull()
    }

    test("view added and removed") {
        val left = schema(views = mapOf("old_v" to view(query = "SELECT 1")))
        val right = schema(views = mapOf("new_v" to view(query = "SELECT 2")))

        val diff = comparator.compare(left, right)
        diff.viewsAdded shouldHaveSize 1
        diff.viewsAdded[0].name shouldBe "new_v"
        diff.viewsRemoved shouldHaveSize 1
        diff.viewsRemoved[0].name shouldBe "old_v"
    }

    test("view materialized change") {
        val left = schema(views = mapOf("v" to view(materialized = false, query = "SELECT 1")))
        val right = schema(views = mapOf("v" to view(materialized = true, query = "SELECT 1")))

        val diff = comparator.compare(left, right)
        diff.viewsChanged shouldHaveSize 1
        diff.viewsChanged[0].materialized.shouldNotBeNull()
        diff.viewsChanged[0].materialized!!.before shouldBe false
        diff.viewsChanged[0].materialized!!.after shouldBe true
    }

    // ===========================================
    // 8.1 Non-compare-relevant fields
    // ===========================================

    test("description changes do not produce diff") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            description = "old",
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            description = "new",
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("partitioning changes do not produce diff") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            partitioning = PartitionConfig(PartitionType.RANGE, emptyList()),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            partitioning = null,
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("CHECK and EXCLUDE constraints are silently ignored") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col()),
            constraints = listOf(
                ConstraintDefinition(name = "chk", type = ConstraintType.CHECK,
                    expression = "c > 0"),
                ConstraintDefinition(name = "excl", type = ConstraintType.EXCLUDE,
                    columns = listOf("c")),
            ),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("c" to col()),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("schema metadata name change") {
        val left = schema(name = "old")
        val right = schema(name = "new")

        val diff = comparator.compare(left, right)
        diff.schemaMetadata.shouldNotBeNull()
        diff.schemaMetadata!!.name.shouldNotBeNull()
        diff.schemaMetadata!!.name!!.before shouldBe "old"
        diff.schemaMetadata!!.name!!.after shouldBe "new"
    }

    test("schema metadata version change") {
        val left = schema(version = "1.0")
        val right = schema(version = "2.0")

        val diff = comparator.compare(left, right)
        diff.schemaMetadata.shouldNotBeNull()
        diff.schemaMetadata!!.version.shouldNotBeNull()
    }

    test("non-compare schema fields do not produce diff") {
        val left = SchemaDefinition(name = "T", version = "1", description = "old",
            encoding = "utf-8", locale = "en_US", schemaFormat = "1.0")
        val right = SchemaDefinition(name = "T", version = "1", description = "new",
            encoding = "latin1", locale = "de_DE", schemaFormat = "2.0")

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("composite and domain custom types are ignored") {
        val left = schema(customTypes = mapOf(
            "addr" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE),
            "posint" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer"),
        ))
        val right = schema()

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    // ===========================================
    // 8.2 Determinismus
    // ===========================================

    test("map order does not affect diff - tables") {
        val cols = mapOf(
            "id" to col(NeutralType.Identifier()),
            "name" to col(NeutralType.Text(50)),
        )
        val left = schema(tables = mapOf(
            "a" to table(columns = cols),
            "b" to table(columns = cols),
        ))
        // LinkedHashMap with reversed insertion order
        val reversedTables = linkedMapOf(
            "b" to table(columns = cols),
            "a" to table(columns = cols),
        )
        val right = schema(tables = reversedTables)

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("map order does not affect diff - columns") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col(), "c" to col()),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = linkedMapOf("c" to col(), "a" to col(), "b" to col()),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("unnamed index uses stable signature") {
        val idx = IndexDefinition(name = null, columns = listOf("a", "b"),
            type = IndexType.BTREE, unique = false)
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col()),
            indices = listOf(idx),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col()),
            indices = listOf(idx),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("unnamed index change detected by signature") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col()),
            indices = listOf(IndexDefinition(name = null, columns = listOf("a"),
                type = IndexType.BTREE, unique = false)),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("a" to col(), "b" to col()),
            indices = listOf(IndexDefinition(name = null, columns = listOf("b"),
                type = IndexType.BTREE, unique = false)),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].indicesAdded shouldHaveSize 1
        diff.tablesChanged[0].indicesRemoved shouldHaveSize 1
    }

    test("repeated comparator run produces same result") {
        val left = schema(
            tables = mapOf(
                "b" to table(columns = mapOf("x" to col())),
                "a" to table(columns = mapOf("y" to col(NeutralType.Integer))),
            ),
            customTypes = mapOf(
                "z" to enumType("a", "b"),
                "m" to enumType("x"),
            ),
        )
        val right = schema(
            tables = mapOf(
                "a" to table(columns = mapOf("y" to col(NeutralType.Text(50)))),
                "c" to table(columns = mapOf("z" to col())),
            ),
            customTypes = mapOf(
                "z" to enumType("a", "b", "c"),
                "n" to enumType("y"),
            ),
        )

        val first = comparator.compare(left, right)
        val second = comparator.compare(left, right)
        first shouldBe second
    }

    test("view description change does not produce diff") {
        val left = schema(views = mapOf("v" to ViewDefinition(
            description = "old desc", query = "SELECT 1",
        )))
        val right = schema(views = mapOf("v" to ViewDefinition(
            description = "new desc", query = "SELECT 1",
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
    }

    test("added tables are sorted by name") {
        val left = schema()
        val right = schema(tables = mapOf(
            "zebra" to table(columns = mapOf("id" to col())),
            "alpha" to table(columns = mapOf("id" to col())),
            "mid" to table(columns = mapOf("id" to col())),
        ))

        val diff = comparator.compare(left, right)
        diff.tablesAdded.map { it.name } shouldBe listOf("alpha", "mid", "zebra")
    }

    test("FK with different onDelete action is detected") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("other", "id",
                    onDelete = ReferentialAction.CASCADE),
            )),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("other", "id",
                    onDelete = ReferentialAction.RESTRICT),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].constraintsChanged shouldHaveSize 1
    }
})
