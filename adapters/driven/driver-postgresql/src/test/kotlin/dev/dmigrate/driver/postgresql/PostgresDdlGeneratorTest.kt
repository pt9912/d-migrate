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
        rendered shouldContain "-- [E053] Function 'mysql_func' was written for 'mysql' and must be manually rewritten for PostgreSQL."
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
        rendered shouldContain "-- [E053] Function 'empty_func' has no body and must be manually implemented."
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

})
