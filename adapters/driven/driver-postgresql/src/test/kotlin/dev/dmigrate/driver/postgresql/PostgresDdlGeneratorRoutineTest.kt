package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class PostgresDdlGeneratorRoutineTest : FunSpec({

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
        ddl shouldContain "CREATE OR REPLACE FUNCTION \"trg_fn_audit_orders\"() RETURNS TRIGGER"
        ddl shouldContain "LANGUAGE plpgsql;"
        ddl shouldContain "CREATE TRIGGER \"audit_orders\""
        ddl shouldContain "AFTER INSERT ON \"orders\""
        ddl shouldContain "FOR EACH ROW"
        ddl shouldContain "EXECUTE FUNCTION \"trg_fn_audit_orders\"();"
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
})
