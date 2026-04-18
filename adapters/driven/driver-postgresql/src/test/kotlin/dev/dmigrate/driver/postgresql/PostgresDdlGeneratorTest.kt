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

class PostgresDdlGeneratorTest : FunSpec({

    val generator = PostgresDdlGenerator()

    // 1. Simple table with columns and PK

    test("simple table with columns and primary key") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer, required = true),
                        "name" to col(NeutralType.Text(maxLength = 100), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TABLE \"users\""
        ddl shouldContain "\"id\" INTEGER NOT NULL"
        ddl shouldContain "\"name\" VARCHAR(100) NOT NULL"
        ddl shouldContain "PRIMARY KEY (\"id\")"
    }

    // 2. SERIAL identifier

    test("SERIAL identifier omits NOT NULL") {
        val s = schema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "\"id\" SERIAL"
        ddl shouldNotContain "\"id\" SERIAL NOT NULL"
    }

    // 3. Foreign key reference

    test("foreign key reference generates CONSTRAINT FOREIGN KEY REFERENCES") {
        val s = schema(
            tables = mapOf(
                "departments" to table(
                    columns = mapOf("id" to col(NeutralType.Integer)),
                    primaryKey = listOf("id")
                ),
                "employees" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "dept_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(
                                table = "departments",
                                column = "id",
                                onDelete = ReferentialAction.CASCADE,
                                onUpdate = ReferentialAction.SET_NULL
                            )
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"fk_employees_dept_id\" FOREIGN KEY (\"dept_id\") REFERENCES \"departments\" (\"id\")"
        ddl shouldContain "ON DELETE CASCADE"
        ddl shouldContain "ON UPDATE SET NULL"
    }

    // 4. Enum with ref_type

    test("enum with ref_type generates CREATE TYPE AS ENUM and column uses quoted type name") {
        val s = schema(
            customTypes = mapOf(
                "status_enum" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("active", "inactive", "pending")
                )
            ),
            tables = mapOf(
                "accounts" to table(
                    columns = mapOf(
                        "status" to col(NeutralType.Enum(refType = "status_enum"), required = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TYPE \"status_enum\" AS ENUM ('active', 'inactive', 'pending');"
        ddl shouldContain "\"status\" \"status_enum\" NOT NULL"
    }

    // 5. Enum with inline values

    test("enum with inline values generates TEXT with CHECK constraint") {
        val s = schema(
            tables = mapOf(
                "orders" to table(
                    columns = mapOf(
                        "priority" to col(
                            NeutralType.Enum(values = listOf("low", "medium", "high")),
                            required = true
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "\"priority\" TEXT NOT NULL"
        ddl shouldContain "CHECK (\"priority\" IN ('low', 'medium', 'high'))"
    }

    // 6. Composite custom type

    test("composite custom type generates CREATE TYPE AS record") {
        val s = schema(
            customTypes = mapOf(
                "address" to CustomTypeDefinition(
                    kind = CustomTypeKind.COMPOSITE,
                    fields = mapOf(
                        "street" to col(NeutralType.Text(maxLength = 200)),
                        "city" to col(NeutralType.Text(maxLength = 100))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TYPE \"address\" AS ("
        ddl shouldContain "\"street\" VARCHAR(200)"
        ddl shouldContain "\"city\" VARCHAR(100)"
    }

    // 7. Domain custom type

    test("domain custom type generates CREATE DOMAIN with CHECK") {
        val s = schema(
            customTypes = mapOf(
                "positive_int" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN,
                    baseType = "INTEGER",
                    check = "VALUE > 0"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE DOMAIN \"positive_int\" AS INTEGER CHECK (VALUE > 0);"
    }

    // 8. Sequence with all options

    test("sequence generates CREATE SEQUENCE with all options") {
        val s = schema(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(
                    start = 100,
                    increment = 5,
                    minValue = 1,
                    maxValue = 999999,
                    cycle = true,
                    cache = 10
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE SEQUENCE \"order_seq\""
        ddl shouldContain "START WITH 100"
        ddl shouldContain "INCREMENT BY 5"
        ddl shouldContain "MINVALUE 1"
        ddl shouldContain "MAXVALUE 999999"
        ddl shouldContain " CYCLE"
        ddl shouldContain "CACHE 10"
    }

    // 9. Index (BTREE default) omits USING

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

    // 10. Index (HASH) includes USING HASH

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

    // 11. Unique index

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

    // 12. View

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

    // 13. Materialized view

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

    // 14. Function with postgresql source_dialect

    test("function with postgresql source_dialect generates CREATE OR REPLACE FUNCTION with body") {
        val s = schema(
            functions = mapOf(
                "add_numbers" to FunctionDefinition(
                    parameters = listOf(
                        ParameterDefinition(name = "a", type = "INTEGER"),
                        ParameterDefinition(name = "b", type = "INTEGER")
                    ),
                    returns = ReturnType(type = "INTEGER"),
                    language = "plpgsql",
                    body = "BEGIN\n    RETURN a + b;\nEND;",
                    sourceDialect = "postgresql"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE OR REPLACE FUNCTION \"add_numbers\""
        ddl shouldContain "\"a\" INTEGER"
        ddl shouldContain "\"b\" INTEGER"
        ddl shouldContain "RETURNS INTEGER"
        ddl shouldContain "BEGIN\n    RETURN a + b;\nEND;"
        ddl shouldContain "LANGUAGE plpgsql;"
    }

    // 15. Function with different source_dialect is skipped with E053

    test("function with non-postgresql source_dialect is skipped with E053") {
        val s = schema(
            functions = mapOf(
                "mysql_func" to FunctionDefinition(
                    body = "BEGIN RETURN 1; END",
                    sourceDialect = "mysql"
                )
            )
        )
        val result = generator.generate(s)
        val rendered = result.render()
        rendered shouldContain "E053"
        rendered shouldContain "TODO"
        result.skippedObjects.any { it.name == "mysql_func" } shouldBe true
    }

    // 16. Function with no body is skipped with E053

    test("function with no body is skipped with E053") {
        val s = schema(
            functions = mapOf(
                "empty_func" to FunctionDefinition(body = null)
            )
        )
        val result = generator.generate(s)
        val rendered = result.render()
        rendered shouldContain "E053"
        rendered shouldContain "TODO"
        result.skippedObjects.any { it.name == "empty_func" } shouldBe true
    }

    // 17. Procedure

    test("procedure generates CREATE OR REPLACE PROCEDURE") {
        val s = schema(
            procedures = mapOf(
                "cleanup" to ProcedureDefinition(
                    parameters = listOf(
                        ParameterDefinition(name = "days_old", type = "INTEGER")
                    ),
                    language = "plpgsql",
                    body = "BEGIN\n    DELETE FROM logs WHERE created_at < NOW() - INTERVAL '1 day' * days_old;\nEND;"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE OR REPLACE PROCEDURE \"cleanup\""
        ddl shouldContain "\"days_old\" INTEGER"
        ddl shouldContain "LANGUAGE plpgsql;"
        ddl shouldContain "DELETE FROM logs"
    }

    // 18. Trigger generates separate trigger function + CREATE TRIGGER

    test("trigger generates trigger function and CREATE TRIGGER with EXECUTE FUNCTION") {
        val s = schema(
            tables = mapOf(
                "orders" to table(columns = mapOf("id" to col(NeutralType.Integer)))
            ),
            triggers = mapOf(
                "audit_orders" to TriggerDefinition(
                    table = "orders",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    forEach = TriggerForEach.ROW,
                    body = "BEGIN\n    INSERT INTO audit_log(table_name) VALUES ('orders');\n    RETURN NEW;\nEND;"
                )
            )
        )
        val ddl = generator.generate(s).render()
        // Trigger function
        ddl shouldContain "CREATE OR REPLACE FUNCTION \"trg_fn_audit_orders\"() RETURNS TRIGGER"
        ddl shouldContain "LANGUAGE plpgsql;"
        // Trigger itself
        ddl shouldContain "CREATE TRIGGER \"audit_orders\""
        ddl shouldContain "AFTER INSERT ON \"orders\""
        ddl shouldContain "FOR EACH ROW"
        ddl shouldContain "EXECUTE FUNCTION \"trg_fn_audit_orders\"();"
    }

    // 19. Circular FK generates ALTER TABLE ADD CONSTRAINT

    test("circular FK generates ALTER TABLE ADD CONSTRAINT") {
        val s = schema(
            tables = mapOf(
                "table_a" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "b_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "table_b", column = "id")
                        )
                    ),
                    primaryKey = listOf("id")
                ),
                "table_b" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "a_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "table_a", column = "id")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "ALTER TABLE"
        ddl shouldContain "ADD CONSTRAINT"
        ddl shouldContain "FOREIGN KEY"
        ddl shouldContain "REFERENCES"
    }

    // 20. Partitioning

    test("partitioning generates PARTITION BY RANGE with sub-partitions") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "event_date" to col(NeutralType.Date)
                    ),
                    primaryKey = listOf("id", "event_date"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("event_date"),
                        partitions = listOf(
                            PartitionDefinition(name = "events_2024", from = "'2024-01-01'", to = "'2025-01-01'"),
                            PartitionDefinition(name = "events_2025", from = "'2025-01-01'", to = "'2026-01-01'")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY RANGE (\"event_date\")"
        ddl shouldContain "CREATE TABLE \"events_2024\" PARTITION OF \"events\" FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');"
        ddl shouldContain "CREATE TABLE \"events_2025\" PARTITION OF \"events\" FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');"
    }

    // 21. Check constraint

    test("check constraint generates CONSTRAINT CHECK") {
        val s = schema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "price" to col(NeutralType.Decimal(10, 2))
                    ),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "chk_price_positive",
                            type = ConstraintType.CHECK,
                            expression = "price > 0"
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"chk_price_positive\" CHECK (price > 0)"
    }

    // 22. Unique constraint

    test("unique constraint generates CONSTRAINT UNIQUE") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "first_name" to col(NeutralType.Text(maxLength = 50)),
                        "last_name" to col(NeutralType.Text(maxLength = 50))
                    ),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "uq_full_name",
                            type = ConstraintType.UNIQUE,
                            columns = listOf("first_name", "last_name")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"uq_full_name\" UNIQUE (\"first_name\", \"last_name\")"
    }

    // 23. Rollback generates DROP statements in reverse order

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

        // Verify reverse order: view dropped before table, table before sequence, sequence before type
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

    // 24. Header contains schema name and dialect

    test("header contains schema name and dialect") {
        val s = schema(name = "my_app_db", version = "2.1")
        val ddl = generator.generate(s).render()
        ddl shouldContain "d-migrate"
        ddl shouldContain "my_app_db"
        ddl shouldContain "2.1"
        ddl shouldContain "postgresql"
    }

    // 25. Table with DEFAULT values

    test("table with DEFAULT string value") {
        val s = schema(
            tables = mapOf(
                "settings" to table(
                    columns = mapOf(
                        "value" to col(NeutralType.Text(), default = DefaultValue.StringLiteral("hello"))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT 'hello'"
    }

    test("table with DEFAULT number value") {
        val s = schema(
            tables = mapOf(
                "counters" to table(
                    columns = mapOf(
                        "count" to col(NeutralType.Integer, default = DefaultValue.NumberLiteral(0))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT 0"
    }

    test("table with DEFAULT boolean value") {
        val s = schema(
            tables = mapOf(
                "flags" to table(
                    columns = mapOf(
                        "active" to col(NeutralType.BooleanType, default = DefaultValue.BooleanLiteral(true))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT TRUE"
    }

    test("table with DEFAULT function call") {
        val s = schema(
            tables = mapOf(
                "records" to table(
                    columns = mapOf(
                        "created_at" to col(
                            NeutralType.DateTime(timezone = true),
                            default = DefaultValue.FunctionCall("current_timestamp")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT CURRENT_TIMESTAMP"
    }

    // 26. Column with NOT NULL and UNIQUE

    test("column with NOT NULL and UNIQUE") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "email" to col(NeutralType.Email, required = true, unique = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "\"email\" VARCHAR(254) NOT NULL"
        ddl shouldContain "UNIQUE"
        // Both modifiers should be on the email column
        val emailLine = ddl.lines().first { it.contains("\"email\"") }
        emailLine shouldContain "NOT NULL"
        emailLine shouldContain "UNIQUE"
    }

    // ── Additional edge-case coverage ──────────────────────

    test("dialect is POSTGRESQL") {
        generator.dialect shouldBe DatabaseDialect.POSTGRESQL
    }

    test("SERIAL identifier with UNIQUE modifier") {
        val s = schema(
            tables = mapOf(
                "tokens" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true), unique = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val idLine = ddl.lines().first { it.contains("\"id\"") }
        idLine shouldContain "SERIAL"
        idLine shouldContain "UNIQUE"
    }

    test("trigger with WHEN condition") {
        val s = schema(
            tables = mapOf(
                "accounts" to table(columns = mapOf("id" to col(NeutralType.Integer)))
            ),
            triggers = mapOf(
                "check_balance" to TriggerDefinition(
                    table = "accounts",
                    event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.BEFORE,
                    forEach = TriggerForEach.ROW,
                    condition = "NEW.balance < 0",
                    body = "BEGIN\n    RAISE EXCEPTION 'negative balance';\nEND;"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "WHEN (NEW.balance < 0)"
        ddl shouldContain "BEFORE UPDATE ON \"accounts\""
    }

    test("function parameter with OUT direction") {
        val s = schema(
            functions = mapOf(
                "get_stats" to FunctionDefinition(
                    parameters = listOf(
                        ParameterDefinition(name = "total", type = "INTEGER", direction = ParameterDirection.OUT)
                    ),
                    returns = ReturnType(type = "VOID"),
                    body = "BEGIN\n    total := 42;\nEND;"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "OUT \"total\" INTEGER"
    }

    test("function defaults to plpgsql language when not specified") {
        val s = schema(
            functions = mapOf(
                "noop" to FunctionDefinition(
                    returns = ReturnType(type = "VOID"),
                    body = "BEGIN\nEND;"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "LANGUAGE plpgsql;"
    }

    test("procedure defaults to plpgsql language when not specified") {
        val s = schema(
            procedures = mapOf(
                "noop_proc" to ProcedureDefinition(
                    body = "BEGIN\nEND;"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "LANGUAGE plpgsql;"
    }

    test("sequence without optional fields omits MINVALUE MAXVALUE CACHE and includes NO CYCLE") {
        val s = schema(
            sequences = mapOf(
                "simple_seq" to SequenceDefinition(start = 1, increment = 1)
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE SEQUENCE \"simple_seq\" START WITH 1 INCREMENT BY 1 NO CYCLE;"
        ddl shouldNotContain "MINVALUE"
        ddl shouldNotContain "MAXVALUE"
        ddl shouldNotContain "CACHE"
    }

    test("domain custom type without CHECK omits CHECK clause") {
        val s = schema(
            customTypes = mapOf(
                "email_type" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN,
                    baseType = "VARCHAR(254)"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE DOMAIN \"email_type\" AS VARCHAR(254);"
        ddl shouldNotContain "CHECK"
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

    test("enum column with ref_type and UNIQUE") {
        val s = schema(
            customTypes = mapOf(
                "color" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("red", "green", "blue")
                )
            ),
            tables = mapOf(
                "themes" to table(
                    columns = mapOf(
                        "primary_color" to col(
                            NeutralType.Enum(refType = "color"),
                            required = true,
                            unique = true
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val colorLine = ddl.lines().first { it.contains("\"primary_color\"") }
        colorLine shouldContain "\"color\""
        colorLine shouldContain "NOT NULL"
        colorLine shouldContain "UNIQUE"
    }

    test("procedure with non-postgresql source_dialect is skipped with E053") {
        val s = schema(
            procedures = mapOf(
                "mysql_proc" to ProcedureDefinition(
                    body = "BEGIN SELECT 1; END",
                    sourceDialect = "mysql"
                )
            )
        )
        val result = generator.generate(s)
        val rendered = result.render()
        rendered shouldContain "E053"
        result.skippedObjects.any { it.name == "mysql_proc" } shouldBe true
    }

    test("SERIAL identifier with DEFAULT value") {
        val s = schema(
            tables = mapOf(
                "things" to table(
                    columns = mapOf(
                        "id" to col(
                            NeutralType.Identifier(autoIncrement = true),
                            default = DefaultValue.NumberLiteral(1000)
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val idLine = ddl.lines().first { it.contains("\"id\"") }
        idLine shouldContain "SERIAL"
        idLine shouldContain "DEFAULT 1000"
    }

    test("enum inline values with DEFAULT") {
        val s = schema(
            tables = mapOf(
                "tasks" to table(
                    columns = mapOf(
                        "status" to col(
                            NeutralType.Enum(values = listOf("open", "closed")),
                            default = DefaultValue.StringLiteral("open")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val statusLine = ddl.lines().first { it.contains("\"status\"") }
        statusLine shouldContain "TEXT"
        statusLine shouldContain "DEFAULT 'open'"
        statusLine shouldContain "CHECK"
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

    test("LIST partitioning generates FOR VALUES IN") {
        val s = schema(tables = mapOf(
            "events" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "region" to col(NeutralType.Text(50))),
                primaryKey = listOf("id", "region"),
                partitioning = PartitionConfig(
                    type = PartitionType.LIST,
                    key = listOf("region"),
                    partitions = listOf(
                        PartitionDefinition(name = "events_eu", values = listOf("'EU'", "'UK'")),
                        PartitionDefinition(name = "events_us", values = listOf("'US'", "'CA'"))
                    )
                )
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY LIST"
        ddl shouldContain "FOR VALUES IN"
        ddl shouldContain "events_eu"
    }

    test("HASH partitioning generates FOR VALUES WITH") {
        val s = schema(tables = mapOf(
            "logs" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "ts" to col(NeutralType.DateTime())),
                primaryKey = listOf("id", "ts"),
                partitioning = PartitionConfig(
                    type = PartitionType.HASH,
                    key = listOf("id"),
                    partitions = listOf(
                        PartitionDefinition(name = "logs_p0", from = "MODULUS 4, REMAINDER 0"),
                        PartitionDefinition(name = "logs_p1", from = "MODULUS 4, REMAINDER 1")
                    )
                )
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY HASH"
        ddl shouldContain "FOR VALUES WITH"
        ddl shouldContain "logs_p0"
    }

    // ── Coverage gap: generateConstraintClause ──

    test("EXCLUDE constraint generates CONSTRAINT EXCLUDE") {
        val s = schema(tables = mapOf(
            "rooms" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "range" to col(NeutralType.Text())),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "no_overlap", type = ConstraintType.EXCLUDE,
                    expression = "range WITH &&"
                ))
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"no_overlap\" EXCLUDE (range WITH &&)"
    }

    test("FOREIGN_KEY constraint via constraint table generates FK clause") {
        val s = schema(tables = mapOf(
            "parents" to table(
                columns = mapOf("id" to col(NeutralType.Integer)),
                primaryKey = listOf("id")
            ),
            "children" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "parent_id" to col(NeutralType.Integer)),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "fk_child_parent", type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("parent_id"),
                    references = ConstraintReferenceDefinition(
                        table = "parents", columns = listOf("id"),
                        onDelete = ReferentialAction.CASCADE, onUpdate = ReferentialAction.SET_NULL
                    )
                ))
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"fk_child_parent\" FOREIGN KEY (\"parent_id\") REFERENCES \"parents\" (\"id\")"
        ddl shouldContain "ON DELETE CASCADE"
        ddl shouldContain "ON UPDATE SET NULL"
    }

    // ── Coverage gap: generateTrigger edge cases ──

    test("trigger without body is skipped with E053") {
        val s = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col(NeutralType.Integer)))),
            triggers = mapOf("trg_no_body" to TriggerDefinition(
                table = "t", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER
            ))
        )
        val result = generator.generate(s)
        result.skippedObjects.any { it.name == "trg_no_body" } shouldBe true
        result.render() shouldContain "E053"
    }

    test("trigger with non-postgresql source_dialect is skipped") {
        val s = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col(NeutralType.Integer)))),
            triggers = mapOf("trg_mysql" to TriggerDefinition(
                table = "t", event = TriggerEvent.UPDATE, timing = TriggerTiming.BEFORE,
                body = "SET NEW.x = 1;", sourceDialect = "mysql"
            ))
        )
        val result = generator.generate(s)
        result.skippedObjects.any { it.name == "trg_mysql" } shouldBe true
    }

    test("trigger with FOR EACH STATEMENT") {
        val s = schema(
            tables = mapOf("t" to table(columns = mapOf("id" to col(NeutralType.Integer)))),
            triggers = mapOf("trg_stmt" to TriggerDefinition(
                table = "t", event = TriggerEvent.DELETE, timing = TriggerTiming.AFTER,
                forEach = TriggerForEach.STATEMENT,
                body = "BEGIN\n    PERFORM 1;\nEND;"
            ))
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "FOR EACH STATEMENT"
        ddl shouldContain "AFTER DELETE"
    }

    // ── Coverage gap: generateView edge cases ──

    test("view without query is skipped") {
        val s = schema(views = mapOf("no_query_view" to ViewDefinition()))
        val result = generator.generate(s)
        result.skippedObjects.any { it.name == "no_query_view" } shouldBe true
    }

    // ── Coverage gap: generateProcedure edge cases ──

    test("procedure without body is skipped with E053") {
        val s = schema(procedures = mapOf("no_body_proc" to ProcedureDefinition()))
        val result = generator.generate(s)
        result.skippedObjects.any { it.name == "no_body_proc" } shouldBe true
        result.render() shouldContain "E053"
    }

    test("procedure with non-postgresql source_dialect is skipped") {
        val s = schema(procedures = mapOf("mysql_proc" to ProcedureDefinition(
            body = "BEGIN SELECT 1; END;", sourceDialect = "mysql"
        )))
        val result = generator.generate(s)
        result.skippedObjects.any { it.name == "mysql_proc" } shouldBe true
    }

    test("procedure with INOUT parameter") {
        val s = schema(procedures = mapOf("swap" to ProcedureDefinition(
            parameters = listOf(
                ParameterDefinition(name = "a", type = "INTEGER", direction = ParameterDirection.INOUT),
                ParameterDefinition(name = "b", type = "INTEGER", direction = ParameterDirection.INOUT)
            ),
            body = "BEGIN\n    -- swap logic\nEND;"
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "INOUT \"a\" INTEGER"
        ddl shouldContain "INOUT \"b\" INTEGER"
    }

    // ── Coverage gap: resolveElementType ──

    test("array with integer element type") {
        val s = schema(tables = mapOf("t" to table(
            columns = mapOf("ids" to col(NeutralType.Array("integer")))
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "INTEGER[]"
    }

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
