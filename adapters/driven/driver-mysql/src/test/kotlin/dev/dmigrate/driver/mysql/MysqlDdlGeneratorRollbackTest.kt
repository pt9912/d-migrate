package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MysqlDdlGeneratorRollbackTest : FunSpec({

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

    test("rollback generates DROP TABLE statement") {
        val schema = emptySchema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP TABLE IF EXISTS `users`;"
    }

    test("rollback generates DROP VIEW for views") {
        val schema = emptySchema(
            views = mapOf(
                "v_summary" to ViewDefinition(query = "SELECT 1")
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP VIEW IF EXISTS `v_summary`;"
    }

    test("rollback generates DROP FUNCTION for delimiter-wrapped functions") {
        val schema = emptySchema(
            functions = mapOf(
                "my_func" to FunctionDefinition(
                    body = "    RETURN 1;",
                    returns = ReturnType(type = "INT"),
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP FUNCTION IF EXISTS `my_func`;"
    }

    test("rollback generates DROP TRIGGER for delimiter-wrapped triggers") {
        val schema = emptySchema(
            triggers = mapOf(
                "trg_test" to TriggerDefinition(
                    table = "test_table",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    body = "    SET @x = 1;",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP TRIGGER IF EXISTS `trg_test`;"
    }

    test("rollback generates DROP INDEX for BTREE index") {
        val schema = emptySchema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "code" to col(NeutralType.Text(maxLength = 20))
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_items_code", columns = listOf("code"), type = IndexType.BTREE)
                    )
                )
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP INDEX IF EXISTS `idx_items_code`;"
    }

    test("rollback reverses statements in opposite order") {
        val schema = emptySchema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "name" to col(NeutralType.Text(maxLength = 100))
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(name = "idx_users_name", columns = listOf("name"), type = IndexType.BTREE)
                    )
                )
            )
        )

        val result = generator.generateRollback(schema)
        val statements = result.statements.map { it.sql }

        val dropIndexPos = statements.indexOfFirst { it.contains("DROP INDEX") }
        val dropTablePos = statements.indexOfFirst { it.contains("DROP TABLE") }
        (dropIndexPos < dropTablePos) shouldBe true
    }

    test("rollback generates DROP PROCEDURE for delimiter-wrapped procedures") {
        val schema = emptySchema(
            procedures = mapOf(
                "my_proc" to ProcedureDefinition(
                    body = "    SELECT 1;",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP PROCEDURE IF EXISTS `my_proc`;"
    }
})
