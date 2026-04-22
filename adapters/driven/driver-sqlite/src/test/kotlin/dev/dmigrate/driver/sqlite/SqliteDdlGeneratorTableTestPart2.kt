package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class SqliteDdlGeneratorTableTestPart2 : FunSpec({

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


    test("inline foreign key with ON UPDATE action") {
        val s = schema(
            tables = mapOf(
                "parents" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                ),
                "children" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "parent_id" to col(
                            NeutralType.Integer,
                            references = ref(
                                "parents", "id",
                                onDelete = ReferentialAction.SET_NULL,
                                onUpdate = ReferentialAction.CASCADE
                            )
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val childrenSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("\"children\"") && it.startsWith("CREATE TABLE") }

        childrenSql shouldContain "REFERENCES \"parents\"(\"id\") ON DELETE SET NULL ON UPDATE CASCADE"
    }

    test("constraint FOREIGN_KEY clause generates correctly") {
        val s = schema(
            tables = mapOf(
                "departments" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                ),
                "employees" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "dept_id" to col(NeutralType.Integer, required = true)
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "fk_emp_dept",
                            type = ConstraintType.FOREIGN_KEY,
                            columns = listOf("dept_id"),
                            references = ConstraintReferenceDefinition(
                                table = "departments",
                                columns = listOf("id"),
                                onDelete = ReferentialAction.CASCADE
                            )
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val empSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("\"employees\"") && it.startsWith("CREATE TABLE") }

        empSql shouldContain "CONSTRAINT \"fk_emp_dept\" FOREIGN KEY (\"dept_id\") REFERENCES \"departments\" (\"id\") ON DELETE CASCADE"
    }

    test("constraint CHECK clause generates correctly") {
        val s = schema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "price" to col(NeutralType.Decimal(10, 2), required = true)
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_positive_price",
                            type = ConstraintType.CHECK,
                            expression = "price > 0"
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "CONSTRAINT \"chk_positive_price\" CHECK (price > 0)"
    }

    test("constraint UNIQUE clause generates correctly") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "email" to col(NeutralType.Email, required = true),
                        "username" to col(NeutralType.Text(), required = true)
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "uq_email_username",
                            type = ConstraintType.UNIQUE,
                            columns = listOf("email", "username")
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "CONSTRAINT \"uq_email_username\" UNIQUE (\"email\", \"username\")"
    }

    test("constraint EXCLUDE is not supported and emits E054") {
        val s = schema(
            tables = mapOf(
                "reservations" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "room" to col(NeutralType.Integer, required = true)
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "excl_room_time",
                            type = ConstraintType.EXCLUDE,
                            expression = "room WITH ="
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)

        result.notes.any { it.code == "E054" && it.objectName == "excl_room_time" } shouldBe true
    }

    test("multiple tables with foreign key dependencies are topologically sorted") {
        val s = schema(
            tables = mapOf(
                "orders" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "customer_id" to col(
                            NeutralType.Integer,
                            required = true,
                            references = ref("customers", "id")
                        )
                    ),
                    primaryKey = listOf("id")
                ),
                "customers" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "name" to col(NeutralType.Text(), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val createTables = result.statements
            .map { it.sql.trim() }
            .filter { it.startsWith("CREATE TABLE") }

        val customersIdx = createTables.indexOfFirst { it.contains("\"customers\"") }
        val ordersIdx = createTables.indexOfFirst { it.contains("\"orders\"") }

        (customersIdx < ordersIdx) shouldBe true
    }

    test("empty schema generates only header") {
        val s = schema()
        val result = generator.generate(s)

        result.statements shouldHaveSize 1
        result.statements.first().sql shouldContain "Generated by d-migrate"
        result.skippedObjects.shouldBeEmpty()
    }

    test("enum column with inline values and default value") {
        val s = schema(
            tables = mapOf(
                "tasks" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "priority" to col(
                            NeutralType.Enum(values = listOf("low", "medium", "high")),
                            required = true,
                            default = DefaultValue.StringLiteral("medium")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"priority\" TEXT NOT NULL DEFAULT 'medium' CHECK (\"priority\" IN ('low', 'medium', 'high'))"
    }

    test("string default value with single quote is escaped") {
        val s = schema(
            tables = mapOf(
                "notes" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "content" to col(
                            NeutralType.Text(),
                            default = DefaultValue.StringLiteral("it's a test")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "DEFAULT 'it''s a test'"
    }

    test("identifier column without autoIncrement still uses INTEGER PRIMARY KEY AUTOINCREMENT") {
        val s = schema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = false)),
                        "name" to col(NeutralType.Text())
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"id\""
    }

    test("number literal default renders correctly") {
        val s = schema(
            tables = mapOf(
                "counters" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "count" to col(
                            NeutralType.Integer,
                            required = true,
                            default = DefaultValue.NumberLiteral(42)
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val sql = result.tableSql()

        sql shouldContain "\"count\" INTEGER NOT NULL DEFAULT 42"
    }

    test("uuid json binary and array retain their SQLite mappings") {
        val s = schema(
            tables = mapOf(
                "typed" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "external_id" to col(NeutralType.Uuid),
                        "payload" to col(NeutralType.Json),
                        "raw_data" to col(NeutralType.Binary),
                        "tags" to col(NeutralType.Array("text")),
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val sql = generator.generate(s).tableSql()

        sql shouldContain "\"external_id\" TEXT"
        sql shouldContain "\"payload\" TEXT"
        sql shouldContain "\"raw_data\" BLOB"
        sql shouldContain "\"tags\" TEXT"
    }

    test("domain custom type emits informational note") {
        val s = schema(
            customTypes = mapOf(
                "positive_int" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN,
                    baseType = "integer",
                    check = "VALUE > 0"
                )
            )
        )
        val result = generator.generate(s)
        val notes = result.notes

        notes.any { it.code == "I001" && it.objectName == "positive_int" } shouldBe true
    }

    test("enum custom type emits informational note") {
        val s = schema(
            customTypes = mapOf(
                "status" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("active", "inactive")
                )
            )
        )
        val result = generator.generate(s)
        val notes = result.notes

        notes.any { it.code == "I001" && it.objectName == "status" } shouldBe true
    }

    test("enum with ref_type and inline reference generates REFERENCES clause") {
        val s = schema(
            customTypes = mapOf(
                "status_type" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("active", "inactive")
                )
            ),
            tables = mapOf(
                "statuses" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                ),
                "accounts" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "status" to col(
                            NeutralType.Enum(refType = "status_type"),
                            required = true,
                            references = ref("statuses", "id", onDelete = ReferentialAction.RESTRICT)
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val accountsSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("\"accounts\"") && it.startsWith("CREATE TABLE") }

        accountsSql shouldContain "CHECK (\"status\" IN ('active', 'inactive'))"
        accountsSql shouldContain "REFERENCES \"statuses\"(\"id\") ON DELETE RESTRICT"
    }

    test("enum with inline values and reference generates both CHECK and REFERENCES") {
        val s = schema(
            tables = mapOf(
                "lookup" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                ),
                "records" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "kind" to col(
                            NeutralType.Enum(values = listOf("a", "b", "c")),
                            required = true,
                            references = ref("lookup", "id", onDelete = ReferentialAction.NO_ACTION)
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generate(s)
        val recordsSql = result.statements
            .map { it.sql.trim() }
            .first { it.contains("\"records\"") && it.startsWith("CREATE TABLE") }

        recordsSql shouldContain "CHECK (\"kind\" IN ('a', 'b', 'c'))"
        recordsSql shouldContain "REFERENCES \"lookup\"(\"id\") ON DELETE NO ACTION"
    }
})
