package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class MysqlDdlGeneratorTestPart2 : FunSpec({

    val generator = MysqlDdlGenerator()

    // ── Helper functions ────────────────────────────────────────

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

    // ── 1. Simple table with ENGINE / CHARSET / COLLATE ─────────


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
        ddl shouldContain "-- TODO: Rewrite trigger `trg_pg` for MySQL (source dialect: postgresql)"
        result.skippedObjects.any { it.name == "trg_pg" } shouldBe true
    }

    // ── 16. Circular FK handled via ALTER TABLE ADD CONSTRAINT ──

    test("circular foreign key generates ALTER TABLE ADD CONSTRAINT") {
        val schema = emptySchema(
            tables = mapOf(
                "table_a" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "b_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "table_b", column = "id")
                        )
                    ),
                    primaryKey = listOf("id")
                ),
                "table_b" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "a_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "table_a", column = "id")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        // Circular references should result in ALTER TABLE statements
        ddl shouldContain "ALTER TABLE"
        ddl shouldContain "ADD CONSTRAINT"
        ddl shouldContain "FOREIGN KEY"
        ddl shouldContain "REFERENCES"
    }

    // ── 17. Boolean default renders 1/0 ─────────────────────────

    test("boolean column with default true renders DEFAULT 1") {
        val schema = emptySchema(
            tables = mapOf(
                "settings" to table(
                    columns = mapOf(
                        "enabled" to col(
                            NeutralType.BooleanType,
                            default = DefaultValue.BooleanLiteral(true)
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`enabled` TINYINT(1) DEFAULT 1"
        ddl shouldNotContain "DEFAULT TRUE"
    }

    test("boolean column with default false renders DEFAULT 0") {
        val schema = emptySchema(
            tables = mapOf(
                "settings" to table(
                    columns = mapOf(
                        "archived" to col(
                            NeutralType.BooleanType,
                            default = DefaultValue.BooleanLiteral(false)
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`archived` TINYINT(1) DEFAULT 0"
        ddl shouldNotContain "DEFAULT FALSE"
    }

    // ── 18. Rollback generates DROP statements ──────────────────

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

    // ── 19. Check constraint ────────────────────────────────────

    test("check constraint generates CONSTRAINT ... CHECK (expression)") {
        val schema = emptySchema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "price" to col(NeutralType.Decimal(10, 2), required = true)
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

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CONSTRAINT `chk_price_positive` CHECK (price > 0)"
    }

    // ── 20. Partitioning inline with PARTITION BY RANGE ─────────

    test("partitioning generates inline PARTITION BY RANGE clause") {
        val schema = emptySchema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "event_date" to col(NeutralType.Date, required = true)
                    ),
                    primaryKey = listOf("id", "event_date"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("event_date"),
                        partitions = listOf(
                            PartitionDefinition(name = "p2024", to = "'2025-01-01'"),
                            PartitionDefinition(name = "p2025", to = "'2026-01-01'"),
                            PartitionDefinition(name = "p_max", to = "MAXVALUE")
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "PARTITION BY RANGE (`event_date`)"
        ddl shouldContain "PARTITION `p2024` VALUES LESS THAN ('2025-01-01')"
        ddl shouldContain "PARTITION `p2025` VALUES LESS THAN ('2026-01-01')"
        ddl shouldContain "PARTITION `p_max` VALUES LESS THAN (MAXVALUE)"
        // Partitioning appears before ENGINE clause
        ddl shouldContain "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"
    }

    // ── Additional edge case tests ──────────────────────────────

    test("index without explicit name auto-generates name") {
        val schema = emptySchema(
            tables = mapOf(
                "things" to table(
                    columns = mapOf(
                        "col_a" to col(NeutralType.Integer),
                        "col_b" to col(NeutralType.Integer)
                    ),
                    indices = listOf(
                        IndexDefinition(name = null, columns = listOf("col_a", "col_b"), type = IndexType.BTREE)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE INDEX `idx_things_col_a_col_b` ON `things` (`col_a`, `col_b`);"
    }

    test("UNIQUE constraint generates CONSTRAINT ... UNIQUE (columns)") {
        val schema = emptySchema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "email" to col(NeutralType.Email, required = true)
                    ),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "uq_users_email",
                            type = ConstraintType.UNIQUE,
                            columns = listOf("email")
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CONSTRAINT `uq_users_email` UNIQUE (`email`)"
    }

    test("EXCLUDE constraint is not supported and emits E054") {
        val schema = emptySchema(
            tables = mapOf(
                "bookings" to table(
                    columns = mapOf(
                        "room_id" to col(NeutralType.Integer),
                        "during" to col(NeutralType.Text())
                    ),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "excl_booking_overlap",
                            type = ConstraintType.EXCLUDE,
                            expression = "USING gist (room_id WITH =, during WITH &&)"
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl.shouldNotContain("-- TODO")
        val note = result.notes.find { it.code == "E054" && it.objectName == "excl_booking_overlap" }
        note!!.type shouldBe NoteType.ACTION_REQUIRED
    }

    test("explicit FOREIGN_KEY constraint generates inline foreign key clause") {
        val schema = emptySchema(
            tables = mapOf(
                "parent" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                ),
                "child" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "parent_id" to col(NeutralType.Integer)
                    ),
                    primaryKey = listOf("id"),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "fk_child_parent",
                            type = ConstraintType.FOREIGN_KEY,
                            columns = listOf("parent_id"),
                            references = ConstraintReferenceDefinition(
                                table = "parent",
                                columns = listOf("id"),
                                onDelete = ReferentialAction.RESTRICT
                            )
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CONSTRAINT `fk_child_parent` FOREIGN KEY (`parent_id`) REFERENCES `parent` (`id`) ON DELETE RESTRICT"
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
        ddl shouldContain "-- TODO: Rewrite procedure `pg_proc` for MySQL (source dialect: postgresql)"
        result.skippedObjects.any { it.name == "pg_proc" } shouldBe true
    }

    test("view with non-mysql source_dialect is transformed and generated") {
        val schema = emptySchema(
            views = mapOf(
                "pg_view" to ViewDefinition(
                    query = "SELECT CURRENT_DATE FROM orders",
                    sourceDialect = "postgresql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE OR REPLACE VIEW `pg_view` AS"
        ddl shouldContain "SELECT CURDATE() FROM orders;"
        result.notes.none { it.code == "E053" && it.objectName == "pg_view" } shouldBe true
        result.skippedObjects.any { it.name == "pg_view" } shouldBe false
    }

    test("header contains schema name, version, and target dialect") {
        val schema = emptySchema()

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- Generated by d-migrate"
        ddl shouldContain "-- Source: neutral schema v1.0 \"test_schema\""
        ddl shouldContain "-- Target: mysql"
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

        // DROP INDEX should come before DROP TABLE (reverse of generation order)
        val dropIndexPos = statements.indexOfFirst { it.contains("DROP INDEX") }
        val dropTablePos = statements.indexOfFirst { it.contains("DROP TABLE") }
        (dropIndexPos < dropTablePos) shouldBe true
    }

    test("LIST partitioning generates PARTITION BY LIST with VALUES IN") {
        val schema = emptySchema(
            tables = mapOf(
                "regional_data" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "region" to col(NeutralType.Text(maxLength = 10), required = true)
                    ),
                    primaryKey = listOf("id", "region"),
                    partitioning = PartitionConfig(
                        type = PartitionType.LIST,
                        key = listOf("region"),
                        partitions = listOf(
                            PartitionDefinition(name = "p_us", values = listOf("'US'", "'CA'")),
                            PartitionDefinition(name = "p_eu", values = listOf("'DE'", "'FR'", "'UK'"))
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "PARTITION BY LIST (`region`)"
        ddl shouldContain "PARTITION `p_us` VALUES IN ('US', 'CA')"
        ddl shouldContain "PARTITION `p_eu` VALUES IN ('DE', 'FR', 'UK')"
    }

})
