package dev.dmigrate.core.diff

import dev.dmigrate.core.identity.ObjectKeyCodec
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
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = name,
        version = version,
        tables = tables,
        customTypes = customTypes,
        views = views,
        sequences = sequences,
        functions = functions,
        procedures = procedures,
        triggers = triggers,
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
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].name shouldBe "status"
        diff.customTypesChanged[0].values.shouldNotBeNull()
        diff.customTypesChanged[0].values!!.before shouldBe listOf("active", "inactive")
        diff.customTypesChanged[0].values!!.after shouldBe listOf("active", "inactive", "archived")
    }

    test("enum custom type added and removed") {
        val left = schema(customTypes = mapOf("old" to enumType("a", "b")))
        val right = schema(customTypes = mapOf("new" to enumType("x", "y")))

        val diff = comparator.compare(left, right)
        diff.customTypesAdded shouldHaveSize 1
        diff.customTypesAdded[0].name shouldBe "new"
        diff.customTypesRemoved shouldHaveSize 1
        diff.customTypesRemoved[0].name shouldBe "old"
    }

    test("enum value order matters") {
        val left = schema(customTypes = mapOf("s" to enumType("a", "b")))
        val right = schema(customTypes = mapOf("s" to enumType("b", "a")))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
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

    test("composite and domain custom types are compare-relevant") {
        val left = schema(customTypes = mapOf(
            "addr" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE),
            "posint" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer"),
        ))
        val right = schema()

        val diff = comparator.compare(left, right)
        diff.customTypesRemoved shouldHaveSize 2
        diff.customTypesRemoved.map { it.name }.sorted() shouldBe listOf("addr", "posint")
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
            views = mapOf(
                "v2" to view(query = "SELECT 1"),
                "v1" to view(query = "SELECT old"),
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
            views = mapOf(
                "v1" to view(query = "SELECT new"),
                "v3" to view(query = "SELECT 3"),
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

    test("added column has unique and references stripped") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "email" to col(NeutralType.Email, unique = true,
                    references = ReferenceDefinition("other", "id")),
            ),
        )))

        val diff = comparator.compare(left, right)
        val addedCol = diff.tablesChanged[0].columnsAdded["email"]!!
        addedCol.unique shouldBe false
        addedCol.references shouldBe null
    }

    test("removed column has unique and references stripped") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf(
                "id" to col(NeutralType.Identifier()),
                "email" to col(NeutralType.Email, unique = true,
                    references = ReferenceDefinition("other", "id")),
            ),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("id" to col(NeutralType.Identifier())),
        )))

        val diff = comparator.compare(left, right)
        val removedCol = diff.tablesChanged[0].columnsRemoved["email"]!!
        removedCol.unique shouldBe false
        removedCol.references shouldBe null
    }

    test("conflicting column-level and constraint-level FK preserves both") {
        val left = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("tableA", "id"),
            )),
            constraints = listOf(ConstraintDefinition(
                name = "fk_ref_b", type = ConstraintType.FOREIGN_KEY,
                columns = listOf("ref"),
                references = ConstraintReferenceDefinition(
                    table = "tableB", columns = listOf("id"),
                ),
            )),
        )))
        val right = schema(tables = mapOf("t" to table(
            columns = mapOf("ref" to col(
                references = ReferenceDefinition("tableA", "id"),
            )),
            constraints = listOf(ConstraintDefinition(
                name = "fk_ref_b", type = ConstraintType.FOREIGN_KEY,
                columns = listOf("ref"),
                references = ConstraintReferenceDefinition(
                    table = "tableB", columns = listOf("id"),
                ),
            )),
        )))

        val diff = comparator.compare(left, right)
        diff.isEmpty() shouldBe true
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

    // ===========================================
    // Phase B: Extended object types
    // ===========================================

    test("domain custom type changed") {
        val left = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0",
            ),
        ))
        val right = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "biginteger", check = "VALUE > 0",
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].name shouldBe "posint"
        diff.customTypesChanged[0].baseType.shouldNotBeNull()
        diff.customTypesChanged[0].baseType!!.before shouldBe "integer"
        diff.customTypesChanged[0].baseType!!.after shouldBe "biginteger"
        diff.customTypesChanged[0].check.shouldBeNull()
    }

    test("domain custom type check changed") {
        val left = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE > 0",
            ),
        ))
        val right = schema(customTypes = mapOf(
            "posint" to CustomTypeDefinition(
                kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE >= 0",
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].check.shouldNotBeNull()
    }

    test("composite custom type added") {
        val left = schema()
        val right = schema(customTypes = mapOf(
            "address" to CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf(
                    "street" to col(NeutralType.Text(200)),
                    "city" to col(NeutralType.Text(100)),
                ),
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesAdded shouldHaveSize 1
        diff.customTypesAdded[0].name shouldBe "address"
    }

    test("composite custom type fields changed") {
        val left = schema(customTypes = mapOf(
            "address" to CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf("street" to col(NeutralType.Text(200))),
            ),
        ))
        val right = schema(customTypes = mapOf(
            "address" to CustomTypeDefinition(
                kind = CustomTypeKind.COMPOSITE,
                fields = mapOf(
                    "street" to col(NeutralType.Text(200)),
                    "city" to col(NeutralType.Text(100)),
                ),
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.customTypesChanged shouldHaveSize 1
        diff.customTypesChanged[0].fields.shouldNotBeNull()
    }

    test("sequence added and removed") {
        val left = schema(sequences = mapOf(
            "old_seq" to SequenceDefinition(start = 1, increment = 1),
        ))
        val right = schema(sequences = mapOf(
            "new_seq" to SequenceDefinition(start = 100, increment = 10),
        ))

        val diff = comparator.compare(left, right)
        diff.sequencesAdded shouldHaveSize 1
        diff.sequencesAdded[0].name shouldBe "new_seq"
        diff.sequencesRemoved shouldHaveSize 1
        diff.sequencesRemoved[0].name shouldBe "old_seq"
    }

    test("sequence changed") {
        val left = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(start = 1, increment = 1, cycle = false),
        ))
        val right = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(start = 1, increment = 1, cycle = true),
        ))

        val diff = comparator.compare(left, right)
        diff.sequencesChanged shouldHaveSize 1
        diff.sequencesChanged[0].name shouldBe "id_seq"
        diff.sequencesChanged[0].cycle.shouldNotBeNull()
        diff.sequencesChanged[0].cycle!!.before shouldBe false
        diff.sequencesChanged[0].cycle!!.after shouldBe true
    }

    test("sequence changed reports increment, min/max, cycle, and cache diffs") {
        val left = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(
                start = 1,
                increment = 1,
                minValue = 10,
                maxValue = 100,
                cycle = false,
                cache = 5,
            ),
        ))
        val right = schema(sequences = mapOf(
            "id_seq" to SequenceDefinition(
                start = 1,
                increment = 10,
                minValue = 20,
                maxValue = 200,
                cycle = true,
                cache = 25,
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.sequencesAdded.shouldBeEmpty()
        diff.sequencesRemoved.shouldBeEmpty()
        diff.sequencesChanged shouldHaveSize 1

        val changed = diff.sequencesChanged[0]
        changed.name shouldBe "id_seq"
        changed.increment.shouldNotBeNull()
        changed.increment!!.before shouldBe 1L
        changed.increment!!.after shouldBe 10L
        changed.minValue.shouldNotBeNull()
        changed.minValue!!.before shouldBe 10L
        changed.minValue!!.after shouldBe 20L
        changed.maxValue.shouldNotBeNull()
        changed.maxValue!!.before shouldBe 100L
        changed.maxValue!!.after shouldBe 200L
        changed.cycle.shouldNotBeNull()
        changed.cycle!!.before shouldBe false
        changed.cycle!!.after shouldBe true
        changed.cache.shouldNotBeNull()
        changed.cache!!.before shouldBe 5
        changed.cache!!.after shouldBe 25
    }

    test("identical sequences produce no diff") {
        val seq = SequenceDefinition(start = 1, increment = 1)
        val left = schema(sequences = mapOf("s" to seq))
        val right = schema(sequences = mapOf("s" to seq))

        val diff = comparator.compare(left, right)
        diff.sequencesChanged.shouldBeEmpty()
    }

    test("function added and removed") {
        val left = schema(functions = mapOf(
            "old_fn" to FunctionDefinition(body = "RETURN 1", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            "new_fn" to FunctionDefinition(body = "RETURN 2", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsAdded shouldHaveSize 1
        diff.functionsAdded[0].name shouldBe "new_fn"
        diff.functionsRemoved shouldHaveSize 1
        diff.functionsRemoved[0].name shouldBe "old_fn"
    }

    test("function changed - body") {
        val left = schema(functions = mapOf(
            "calc" to FunctionDefinition(body = "RETURN a + b", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            "calc" to FunctionDefinition(body = "RETURN a * b", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].body.shouldNotBeNull()
    }

    test("function changed - parameters") {
        val left = schema(functions = mapOf(
            "calc" to FunctionDefinition(
                parameters = listOf(ParameterDefinition("x", "integer")),
                language = "sql",
            ),
        ))
        val right = schema(functions = mapOf(
            "calc" to FunctionDefinition(
                parameters = listOf(
                    ParameterDefinition("x", "integer"),
                    ParameterDefinition("y", "integer"),
                ),
                language = "sql",
            ),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].parameters.shouldNotBeNull()
    }

    test("procedure added and removed") {
        val left = schema(procedures = mapOf(
            "old_proc" to ProcedureDefinition(body = "DELETE FROM t", language = "sql"),
        ))
        val right = schema(procedures = mapOf(
            "new_proc" to ProcedureDefinition(body = "INSERT INTO t", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.proceduresAdded shouldHaveSize 1
        diff.proceduresAdded[0].name shouldBe "new_proc"
        diff.proceduresRemoved shouldHaveSize 1
        diff.proceduresRemoved[0].name shouldBe "old_proc"
    }

    test("procedure changed - language") {
        val left = schema(procedures = mapOf(
            "proc" to ProcedureDefinition(body = "body", language = "sql"),
        ))
        val right = schema(procedures = mapOf(
            "proc" to ProcedureDefinition(body = "body", language = "plpgsql"),
        ))

        val diff = comparator.compare(left, right)
        diff.proceduresChanged shouldHaveSize 1
        diff.proceduresChanged[0].language.shouldNotBeNull()
    }

    test("trigger added and removed") {
        val left = schema(
            tables = mapOf("users" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "old_trg" to TriggerDefinition(
                    table = "users", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.BEFORE, body = "old body",
                ),
            ),
        )
        val right = schema(
            tables = mapOf("users" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "new_trg" to TriggerDefinition(
                    table = "users", event = TriggerEvent.DELETE,
                    timing = TriggerTiming.AFTER, body = "new body",
                ),
            ),
        )

        val diff = comparator.compare(left, right)
        diff.triggersAdded shouldHaveSize 1
        diff.triggersAdded[0].name shouldBe "new_trg"
        diff.triggersRemoved shouldHaveSize 1
        diff.triggersRemoved[0].name shouldBe "old_trg"
    }

    test("trigger changed - event and timing") {
        val left = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "trg" to TriggerDefinition(
                    table = "t", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.BEFORE,
                ),
            ),
        )
        val right = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col()))),
            triggers = mapOf(
                "trg" to TriggerDefinition(
                    table = "t", event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.AFTER,
                ),
            ),
        )

        val diff = comparator.compare(left, right)
        diff.triggersChanged shouldHaveSize 1
        diff.triggersChanged[0].event.shouldNotBeNull()
        diff.triggersChanged[0].timing.shouldNotBeNull()
    }

    // ===========================================
    // Phase B: Canonical key identity
    // ===========================================

    test("overloaded functions with canonical keys are tracked separately") {
        val paramsInt = listOf(ParameterDefinition("x", "integer"))
        val paramsText = listOf(ParameterDefinition("x", "text"))
        val keyInt = ObjectKeyCodec.routineKey("calc", paramsInt)
        val keyText = ObjectKeyCodec.routineKey("calc", paramsText)

        val left = schema(functions = mapOf(
            keyInt to FunctionDefinition(parameters = paramsInt, body = "RETURN x + 1", language = "sql"),
            keyText to FunctionDefinition(parameters = paramsText, body = "RETURN x || '!'", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            keyInt to FunctionDefinition(parameters = paramsInt, body = "RETURN x + 2", language = "sql"),
            keyText to FunctionDefinition(parameters = paramsText, body = "RETURN x || '!'", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        // Only the integer overload changed; text overload is identical
        diff.functionsChanged shouldHaveSize 1
        diff.functionsChanged[0].name shouldBe keyInt
        diff.functionsChanged[0].body.shouldNotBeNull()
        diff.functionsAdded.shouldBeEmpty()
        diff.functionsRemoved.shouldBeEmpty()
    }

    test("overloaded function added as new overload") {
        val paramsInt = listOf(ParameterDefinition("x", "integer"))
        val paramsIntInt = listOf(ParameterDefinition("x", "integer"), ParameterDefinition("y", "integer"))
        val keyOne = ObjectKeyCodec.routineKey("calc", paramsInt)
        val keyTwo = ObjectKeyCodec.routineKey("calc", paramsIntInt)

        val left = schema(functions = mapOf(
            keyOne to FunctionDefinition(parameters = paramsInt, body = "RETURN x", language = "sql"),
        ))
        val right = schema(functions = mapOf(
            keyOne to FunctionDefinition(parameters = paramsInt, body = "RETURN x", language = "sql"),
            keyTwo to FunctionDefinition(parameters = paramsIntInt, body = "RETURN x + y", language = "sql"),
        ))

        val diff = comparator.compare(left, right)
        diff.functionsAdded shouldHaveSize 1
        diff.functionsAdded[0].name shouldBe keyTwo
        diff.functionsChanged.shouldBeEmpty()
    }

    test("same-named triggers on different tables use canonical keys without collision") {
        val keyUsersAudit = ObjectKeyCodec.triggerKey("users", "audit")
        val keyOrdersAudit = ObjectKeyCodec.triggerKey("orders", "audit")

        val left = schema(
            tables = mapOf(
                "users" to table(columns = mapOf("id" to col())),
                "orders" to table(columns = mapOf("id" to col())),
            ),
            triggers = mapOf(
                keyUsersAudit to TriggerDefinition(
                    table = "users", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "user audit v1",
                ),
                keyOrdersAudit to TriggerDefinition(
                    table = "orders", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "order audit",
                ),
            ),
        )
        val right = schema(
            tables = mapOf(
                "users" to table(columns = mapOf("id" to col())),
                "orders" to table(columns = mapOf("id" to col())),
            ),
            triggers = mapOf(
                keyUsersAudit to TriggerDefinition(
                    table = "users", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "user audit v2",
                ),
                keyOrdersAudit to TriggerDefinition(
                    table = "orders", event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER, body = "order audit",
                ),
            ),
        )

        val diff = comparator.compare(left, right)
        // Only the users trigger changed; orders trigger is identical
        diff.triggersChanged shouldHaveSize 1
        diff.triggersChanged[0].name shouldBe keyUsersAudit
        diff.triggersChanged[0].body.shouldNotBeNull()
        diff.triggersAdded.shouldBeEmpty()
        diff.triggersRemoved.shouldBeEmpty()
    }

    // ===========================================
    // Phase B: Table metadata
    // ===========================================

    test("table metadata engine change detected") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "InnoDB"),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "MyISAM"),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].metadata.shouldNotBeNull()
        diff.tablesChanged[0].metadata!!.before shouldBe TableMetadata(engine = "InnoDB")
        diff.tablesChanged[0].metadata!!.after shouldBe TableMetadata(engine = "MyISAM")
    }

    test("table metadata withoutRowid change detected") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(withoutRowid = false),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(withoutRowid = true),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged shouldHaveSize 1
        diff.tablesChanged[0].metadata.shouldNotBeNull()
    }

    test("identical table metadata produces no diff") {
        val left = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "InnoDB"),
        )))
        val right = schema(tables = mapOf("t" to TableDefinition(
            columns = mapOf("id" to col(NeutralType.Identifier())),
            metadata = TableMetadata(engine = "InnoDB"),
        )))

        val diff = comparator.compare(left, right)
        diff.tablesChanged.shouldBeEmpty()
    }
})
