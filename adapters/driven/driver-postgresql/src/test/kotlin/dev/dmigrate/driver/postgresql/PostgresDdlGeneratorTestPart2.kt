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

class PostgresDdlGeneratorTestPart2 : FunSpec({

    val generator = PostgresDdlGenerator()

    // 1. Simple table with columns and PK


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

})
