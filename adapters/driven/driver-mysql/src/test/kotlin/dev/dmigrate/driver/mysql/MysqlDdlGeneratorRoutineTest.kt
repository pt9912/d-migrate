package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MysqlDdlGeneratorRoutineTest : FunSpec({

    val generator = MysqlDdlGenerator()

    fun emptySchema(
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = "test_schema",
        version = "1.0",
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers
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

    test("function with mysql source_dialect generates DELIMITER-wrapped body") {
        val schema = emptySchema(
            functions = mapOf(
                "calc_tax" to FunctionDefinition(
                    parameters = listOf(
                        ParameterDefinition(name = "amount", type = "DECIMAL(10,2)")
                    ),
                    returns = ReturnType(type = "DECIMAL(10,2)"),
                    deterministic = true,
                    body = "    RETURN amount * 0.08;",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "DELIMITER //"
        ddl shouldContain "CREATE FUNCTION `calc_tax`(`amount` DECIMAL(10,2))"
        ddl shouldContain "RETURNS DECIMAL(10,2)"
        ddl shouldContain "DETERMINISTIC"
        ddl shouldContain "BEGIN"
        ddl shouldContain "    RETURN amount * 0.08;"
        ddl shouldContain "END //"
        ddl shouldContain "DELIMITER ;"
    }

    test("function with no source_dialect generates DELIMITER-wrapped body") {
        val schema = emptySchema(
            functions = mapOf(
                "get_name" to FunctionDefinition(
                    parameters = emptyList(),
                    returns = ReturnType(type = "VARCHAR(100)"),
                    deterministic = false,
                    body = "    RETURN 'hello';",
                    sourceDialect = null
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "DELIMITER //"
        ddl shouldContain "CREATE FUNCTION `get_name`()"
        ddl shouldContain "NOT DETERMINISTIC"
        ddl shouldContain "END //"
        ddl shouldContain "DELIMITER ;"
    }

    test("function with non-mysql source_dialect is skipped with E053") {
        val schema = emptySchema(
            functions = mapOf(
                "pg_func" to FunctionDefinition(
                    body = "RETURN NEW;",
                    sourceDialect = "postgresql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E053"
        ddl shouldContain "-- [E053] Function 'pg_func' was written for 'postgresql' and must be manually rewritten for MySQL."

        val note = result.notes.find { it.code == "E053" && it.objectName == "pg_func" }
        note!!.message shouldContain "must be manually rewritten for MySQL"
        result.skippedObjects.any { it.name == "pg_func" } shouldBe true
    }

    test("trigger generates DELIMITER-wrapped DDL with timing and event") {
        val schema = emptySchema(
            tables = mapOf(
                "audit_log" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    )
                )
            ),
            triggers = mapOf(
                "trg_audit_insert" to TriggerDefinition(
                    table = "audit_log",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.BEFORE,
                    forEach = TriggerForEach.ROW,
                    body = "    SET NEW.id = NEW.id + 1;",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "DELIMITER //"
        ddl shouldContain "CREATE TRIGGER `trg_audit_insert`"
        ddl shouldContain "BEFORE INSERT ON `audit_log`"
        ddl shouldContain "FOR EACH ROW"
        ddl shouldContain "BEGIN"
        ddl shouldContain "    SET NEW.id = NEW.id + 1;"
        ddl shouldContain "END //"
        ddl shouldContain "DELIMITER ;"
    }

    test("trigger with non-mysql source_dialect is skipped with E053") {
        val schema = emptySchema(
            triggers = mapOf(
                "trg_pg" to TriggerDefinition(
                    table = "events",
                    event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.AFTER,
                    body = "EXECUTE FUNCTION notify();",
                    sourceDialect = "postgresql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E053"
        ddl shouldContain "-- [E053] Trigger 'trg_pg' was written for 'postgresql' and must be manually rewritten for MySQL."
        result.skippedObjects.any { it.name == "trg_pg" } shouldBe true
    }

    test("function with OUT parameter direction is included in signature") {
        val schema = emptySchema(
            functions = mapOf(
                "get_total" to FunctionDefinition(
                    parameters = listOf(
                        ParameterDefinition(name = "in_id", type = "INT", direction = ParameterDirection.IN),
                        ParameterDefinition(name = "out_total", type = "DECIMAL(10,2)", direction = ParameterDirection.OUT)
                    ),
                    returns = ReturnType(type = "INT"),
                    body = "    SET out_total = 100.00;\n    RETURN 0;",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`in_id` INT"
        ddl shouldContain "OUT `out_total` DECIMAL(10,2)"
    }

    test("procedure with mysql source_dialect generates DELIMITER-wrapped DDL") {
        val schema = emptySchema(
            procedures = mapOf(
                "cleanup" to ProcedureDefinition(
                    parameters = listOf(
                        ParameterDefinition(name = "days_old", type = "INT")
                    ),
                    body = "    DELETE FROM logs WHERE created_at < DATE_SUB(NOW(), INTERVAL days_old DAY);",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "DELIMITER //"
        ddl shouldContain "CREATE PROCEDURE `cleanup`(`days_old` INT)"
        ddl shouldContain "BEGIN"
        ddl shouldContain "END //"
        ddl shouldContain "DELIMITER ;"
    }

    test("procedure with non-mysql source_dialect is skipped with E053") {
        val schema = emptySchema(
            procedures = mapOf(
                "pg_proc" to ProcedureDefinition(
                    body = "RAISE NOTICE 'hello';",
                    sourceDialect = "postgresql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E053"
        ddl shouldContain "-- [E053] Procedure 'pg_proc' was written for 'postgresql' and must be manually rewritten for MySQL."
        result.skippedObjects.any { it.name == "pg_proc" } shouldBe true
    }

    test("function with no body emits E053 and is skipped") {
        val schema = emptySchema(
            functions = mapOf(
                "stub_func" to FunctionDefinition(
                    body = null,
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- [E053] Function 'stub_func' has no body and must be manually implemented."
        val note = result.notes.find { it.code == "E053" && it.objectName == "stub_func" }
        note!!.message shouldContain "has no body and must be manually implemented"
        result.skippedObjects.any { it.name == "stub_func" } shouldBe true
    }

    test("trigger with no body emits E053 and is skipped") {
        val schema = emptySchema(
            triggers = mapOf(
                "stub_trg" to TriggerDefinition(
                    table = "events",
                    event = TriggerEvent.DELETE,
                    timing = TriggerTiming.BEFORE,
                    body = null
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- [E053] Trigger 'stub_trg' has no body and must be manually implemented."
        val note = result.notes.find { it.code == "E053" && it.objectName == "stub_trg" }
        note!!.message shouldContain "has no body and must be manually implemented"
        result.skippedObjects.any { it.name == "stub_trg" } shouldBe true
    }
})
