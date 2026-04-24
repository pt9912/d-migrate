package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SqliteDdlGeneratorTableTest : FunSpec({

    val generator = SqliteDdlGenerator()

    fun schema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = "test-schema",
        version = "1.0.0",
        customTypes = customTypes,
        tables = tables,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers
    )

    fun table(
        columns: Map<String, ColumnDefinition> = emptyMap(),
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

    fun ref(
        table: String,
        column: String,
        onDelete: ReferentialAction? = null,
        onUpdate: ReferentialAction? = null
    ) = ReferenceDefinition(table, column, onDelete, onUpdate)

    fun DdlResult.tableSql(): String {
        return statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE TABLE") }
    }

    test("simple table uses double-quoted identifiers and CREATE TABLE") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "name" to col(NeutralType.Text(maxLength = 100), required = true),
                        "email" to col(NeutralType.Email, required = true, unique = true)
                    )
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "CREATE TABLE \"users\""
        sql shouldContain "\"name\" TEXT NOT NULL"
        sql shouldContain "\"email\" TEXT NOT NULL UNIQUE"
    }

    test("identifier column produces INTEGER PRIMARY KEY AUTOINCREMENT without separate PK clause") {
        val s = schema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "label" to col(NeutralType.Text(), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"id\" INTEGER PRIMARY KEY AUTOINCREMENT"
        sql shouldNotContain "PRIMARY KEY (\"id\")"
    }

    test("composite primary key emits separate PRIMARY KEY clause") {
        val s = schema(
            tables = mapOf(
                "order_items" to table(
                    columns = mapOf(
                        "order_id" to col(NeutralType.Integer, required = true),
                        "product_id" to col(NeutralType.Integer, required = true),
                        "quantity" to col(NeutralType.Integer, required = true)
                    ),
                    primaryKey = listOf("order_id", "product_id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "PRIMARY KEY (\"order_id\", \"product_id\")"
    }

    test("inline foreign key produces REFERENCES with ON DELETE action") {
        val s = schema(
            tables = mapOf(
                "categories" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                ),
                "products" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "category_id" to col(
                            NeutralType.Integer,
                            required = true,
                            references = ref("categories", "id", onDelete = ReferentialAction.CASCADE)
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val productsSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("\"products\"") && it.startsWith("CREATE TABLE") }

        productsSql shouldContain "REFERENCES \"categories\"(\"id\") ON DELETE CASCADE"
    }

    test("enum with inline values produces TEXT with CHECK constraint") {
        val s = schema(
            tables = mapOf(
                "tasks" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "status" to col(
                            NeutralType.Enum(values = listOf("open", "in_progress", "done")),
                            required = true
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"status\" TEXT NOT NULL CHECK (\"status\" IN ('open', 'in_progress', 'done'))"
    }

    test("enum with ref_type resolves values from custom types and produces CHECK constraint") {
        val s = schema(
            customTypes = mapOf(
                "priority_level" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("low", "medium", "high", "critical")
                )
            ),
            tables = mapOf(
                "tickets" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "priority" to col(
                            NeutralType.Enum(refType = "priority_level"),
                            required = true
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"priority\" TEXT NOT NULL CHECK (\"priority\" IN ('low', 'medium', 'high', 'critical'))"
    }

    test("composite custom type emits E054 action required note") {
        val s = schema(
            customTypes = mapOf(
                "address" to CustomTypeDefinition(
                    kind = CustomTypeKind.COMPOSITE,
                    fields = mapOf(
                        "street" to col(NeutralType.Text()),
                        "city" to col(NeutralType.Text())
                    )
                )
            )
        )
        val result = generator.generate(s)
        val notes = result.notes

        notes.any { it.code == "E054" && it.objectName == "address" } shouldBe true
        notes.any { it.message.contains("Composite type") } shouldBe true
    }

    test("sequence is skipped with E056 and added to skippedObjects") {
        val s = schema(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(start = 1000, increment = 1)
            )
        )
        val result = generator.generate(s)

        result.skippedObjects.any { it.type == "sequence" && it.name == "order_seq" } shouldBe true
        result.notes.any { it.code == "E056" && it.objectName == "order_seq" } shouldBe true
    }

    test("table with partitioning emits E055 warning") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "created_at" to col(NeutralType.DateTime(), required = true)
                    ),
                    primaryKey = listOf("id"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("created_at")
                    )
                )
            )
        )
        val result = generator.generate(s)
        val notes = result.notes

        notes.any { it.code == "E055" && it.message.contains("partitioning") } shouldBe true
    }

    test("circular foreign keys produce E019 error and are added to skipped objects") {
        val s = schema(
            tables = mapOf(
                "table_a" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "b_id" to col(
                            NeutralType.Integer,
                            references = ref("table_b", "id")
                        )
                    ),
                    primaryKey = listOf("id")
                ),
                "table_b" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "a_id" to col(
                            NeutralType.Integer,
                            references = ref("table_a", "id")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)

        result.notes.any { it.code == "E019" } shouldBe true
        result.skippedObjects.any { it.type == "foreign_key" } shouldBe true
    }

    test("decimal column maps to REAL with W200 precision loss warning") {
        val s = schema(
            tables = mapOf(
                "invoices" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "amount" to col(NeutralType.Decimal(precision = 18, scale = 4), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"amount\" REAL NOT NULL"
        result.notes.any {
            it.code == "W200" && it.objectName == "invoices.amount" && it.message.contains("Decimal(18,4)")
        } shouldBe true
    }

    test("boolean column with default true renders DEFAULT 1") {
        val s = schema(
            tables = mapOf(
                "settings" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "active" to col(
                            NeutralType.BooleanType,
                            required = true,
                            default = DefaultValue.BooleanLiteral(true)
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"active\" INTEGER NOT NULL DEFAULT 1"
    }

    test("boolean column with default false renders DEFAULT 0") {
        val s = schema(
            tables = mapOf(
                "settings" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "disabled" to col(
                            NeutralType.BooleanType,
                            required = true,
                            default = DefaultValue.BooleanLiteral(false)
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"disabled\" INTEGER NOT NULL DEFAULT 0"
    }

    test("datetime column with current_timestamp default renders datetime now") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "created_at" to col(
                            NeutralType.DateTime(),
                            required = true,
                            default = DefaultValue.FunctionCall("current_timestamp")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "DEFAULT (datetime('now'))"
    }

    test("generated DDL does not contain ENGINE or CHARSET table options") {
        val s = schema(
            tables = mapOf(
                "simple" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "value" to col(NeutralType.Text())
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val rendered = result.render()

        rendered shouldNotContain "ENGINE"
        rendered shouldNotContain "CHARSET"
        rendered shouldNotContain "COLLATE"
        rendered shouldNotContain "DEFAULT CHARSET"
    }

    test("dialect is SQLITE") {
        generator.dialect shouldBe dev.dmigrate.driver.DatabaseDialect.SQLITE
    }

    test("header contains schema name and target sqlite") {
        val s = schema()
        val result = generator.generate(s)
        val header = result.statements.first().sql

        header shouldContain "d-migrate"
        header shouldContain "test-schema"
        header shouldContain "sqlite"
    }

    test("enum with ref_type but no custom type definition still produces TEXT without CHECK") {
        val s = schema(
            tables = mapOf(
                "records" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "category" to col(
                            NeutralType.Enum(refType = "missing_type"),
                            required = true
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"category\" TEXT NOT NULL"
        sql shouldNotContain "CHECK"
    }

})
