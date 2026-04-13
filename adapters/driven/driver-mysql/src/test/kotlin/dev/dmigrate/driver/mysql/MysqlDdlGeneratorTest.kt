package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.SpatialProfile
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith

class MysqlDdlGeneratorTest : FunSpec({

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

    test("simple table generates CREATE TABLE with ENGINE=InnoDB DEFAULT CHARSET and COLLATE") {
        val schema = emptySchema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "name" to col(NeutralType.Text(maxLength = 100), required = true)
                    ),
                    primaryKey = emptyList()
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"
        ddl shouldContain "CREATE TABLE `users`"
        ddl shouldContain "`name` VARCHAR(100) NOT NULL"
    }

    // ── 2. AUTO_INCREMENT identifier ────────────────────────────

    test("auto-increment identifier column renders INT NOT NULL AUTO_INCREMENT") {
        val schema = emptySchema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`id` INT NOT NULL AUTO_INCREMENT"
        ddl shouldContain "PRIMARY KEY (`id`)"
    }

    // ── 3. Backtick quoting ─────────────────────────────────────

    test("all identifiers are quoted with backticks") {
        val schema = emptySchema(
            tables = mapOf(
                "my_table" to table(
                    columns = mapOf(
                        "my_column" to col(NeutralType.Integer, required = true)
                    ),
                    primaryKey = listOf("my_column")
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`my_table`"
        ddl shouldContain "`my_column`"
        ddl shouldNotContain "\"my_table\""
        ddl shouldNotContain "\"my_column\""
    }

    // ── 4. Foreign key constraint from column references ────────

    test("foreign key generates CONSTRAINT ... FOREIGN KEY ... REFERENCES with actions") {
        val schema = emptySchema(
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
                        "dept_id" to col(
                            NeutralType.Integer, required = true,
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

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CONSTRAINT `fk_employees_dept_id` FOREIGN KEY (`dept_id`) REFERENCES `departments` (`id`)"
        ddl shouldContain "ON DELETE CASCADE"
        ddl shouldContain "ON UPDATE SET NULL"
    }

    // ── 5. Enum with ref_type resolves values from customTypes ──

    test("enum with ref_type resolves values from customTypes and inlines ENUM(...)") {
        val schema = emptySchema(
            customTypes = mapOf(
                "status_type" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("active", "inactive", "pending")
                )
            ),
            tables = mapOf(
                "accounts" to table(
                    columns = mapOf(
                        "status" to col(
                            NeutralType.Enum(refType = "status_type"),
                            required = true
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "ENUM('active', 'inactive', 'pending')"
        ddl shouldContain "`status` ENUM('active', 'inactive', 'pending') NOT NULL"
    }

    // ── 6. Enum with inline values ──────────────────────────────

    test("enum with inline values generates inline ENUM(...)") {
        val schema = emptySchema(
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

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`priority` ENUM('low', 'medium', 'high') NOT NULL"
    }

    // ── 7. Composite custom type skipped with E052 ──────────────

    test("composite custom type is skipped with E052 action required note") {
        val schema = emptySchema(
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

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E052"
        ddl shouldContain "Composite type 'address' is not supported in MySQL"

        val notes = result.notes
        val e052 = notes.find { it.code == "E052" && it.objectName == "address" }
        e052 shouldBe TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E052",
            objectName = "address",
            message = "Composite type 'address' is not supported in MySQL and was skipped.",
            hint = "Consider restructuring the data model to avoid composite types."
        )
    }

    // ── 8. Sequence skipped with E052 ───────────────────────────

    test("sequence is skipped with E052 action required note") {
        val schema = emptySchema(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(start = 1, increment = 1)
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E052"
        ddl shouldContain "Sequence 'order_seq' is not supported in MySQL"

        val notes = result.notes
        val seqNote = notes.find { it.code == "E052" && it.objectName == "order_seq" }
        seqNote shouldBe TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E052",
            objectName = "order_seq",
            message = "Sequence 'order_seq' is not supported in MySQL and was skipped.",
            hint = "Use AUTO_INCREMENT columns instead of sequences."
        )

        result.skippedObjects.any { it.name == "order_seq" && it.type == "sequence" } shouldBe true
    }

    // ── 9. HASH index warning W102, converted to BTREE ──────────

    test("HASH index emits W102 warning and converts to BTREE") {
        val schema = emptySchema(
            tables = mapOf(
                "lookups" to table(
                    columns = mapOf(
                        "key" to col(NeutralType.Text(maxLength = 64), required = true)
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_lookups_key",
                            columns = listOf("key"),
                            type = IndexType.HASH
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "USING BTREE"
        ddl shouldContain "W102"
        ddl shouldContain "HASH index 'idx_lookups_key' is not supported on InnoDB; converted to BTREE."

        val w102 = result.notes.find { it.code == "W102" && it.objectName == "idx_lookups_key" }
        w102!!.type shouldBe NoteType.WARNING
    }

    // ── 10. GIN / GIST / BRIN index skipped with W102 ───────────

    test("GIN index is skipped with W102 warning") {
        val schema = emptySchema(
            tables = mapOf(
                "docs" to table(
                    columns = mapOf(
                        "body" to col(NeutralType.Json)
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_docs_body", columns = listOf("body"), type = IndexType.GIN)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- TODO: GIN index `idx_docs_body` is not supported in MySQL"
        ddl shouldContain "W102"

        val note = result.notes.find { it.code == "W102" && it.objectName == "idx_docs_body" }
        note!!.type shouldBe NoteType.WARNING
        note.message shouldContain "GIN index 'idx_docs_body' is not supported in MySQL and was skipped."
    }

    test("GIST index is skipped with W102 warning") {
        val schema = emptySchema(
            tables = mapOf(
                "geo" to table(
                    columns = mapOf(
                        "location" to col(NeutralType.Text())
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_geo_loc", columns = listOf("location"), type = IndexType.GIST)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "GIST index 'idx_geo_loc' is not supported in MySQL"
        result.notes.any { it.code == "W102" && it.objectName == "idx_geo_loc" } shouldBe true
    }

    test("BRIN index is skipped with W102 warning") {
        val schema = emptySchema(
            tables = mapOf(
                "logs" to table(
                    columns = mapOf(
                        "created_at" to col(NeutralType.DateTime())
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_logs_created", columns = listOf("created_at"), type = IndexType.BRIN)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "BRIN index 'idx_logs_created' is not supported in MySQL"
        result.notes.any { it.code == "W102" && it.objectName == "idx_logs_created" } shouldBe true
    }

    // ── 11. BTREE index generates CREATE INDEX ──────────────────

    test("BTREE index generates CREATE INDEX without warnings") {
        val schema = emptySchema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "sku" to col(NeutralType.Text(maxLength = 50), required = true)
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_products_sku", columns = listOf("sku"), type = IndexType.BTREE)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE INDEX `idx_products_sku` ON `products` (`sku`);"
        result.notes.filter { it.objectName == "idx_products_sku" }.shouldBeEmpty()
    }

    test("unique BTREE index generates CREATE UNIQUE INDEX") {
        val schema = emptySchema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "sku" to col(NeutralType.Text(maxLength = 50), required = true)
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_products_sku_unique",
                            columns = listOf("sku"),
                            type = IndexType.BTREE,
                            unique = true
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE UNIQUE INDEX `idx_products_sku_unique` ON `products` (`sku`);"
    }

    test("uuid json binary and array retain their MySQL mappings") {
        val schema = emptySchema(
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

        val ddl = generator.generate(schema).render()

        ddl shouldContain "`external_id` CHAR(36)"
        ddl shouldContain "`payload` JSON"
        ddl shouldContain "`raw_data` BLOB"
        ddl shouldContain "`tags` JSON"
    }

    // ── 12. Materialized view becomes regular VIEW + W103 ───────

    test("materialized view creates regular VIEW with W103 warning") {
        val schema = emptySchema(
            views = mapOf(
                "active_users_mv" to ViewDefinition(
                    materialized = true,
                    query = "SELECT * FROM users WHERE active = 1"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE OR REPLACE VIEW `active_users_mv` AS"
        ddl shouldContain "SELECT * FROM users WHERE active = 1"
        ddl shouldNotContain "MATERIALIZED"

        val w103 = result.notes.find { it.code == "W103" && it.objectName == "active_users_mv" }
        w103!!.type shouldBe NoteType.WARNING
        w103.message shouldContain "Materialized views are not supported in MySQL"
    }

    test("regular view creates CREATE OR REPLACE VIEW without warnings") {
        val schema = emptySchema(
            views = mapOf(
                "active_users" to ViewDefinition(
                    query = "SELECT * FROM users WHERE active = 1"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE OR REPLACE VIEW `active_users` AS\nSELECT * FROM users WHERE active = 1;"
        result.notes.filter { it.objectName == "active_users" }.shouldBeEmpty()
    }

    // ── 13. Function with mysql source_dialect ───────────────────

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

    // ── 14. Function with different source_dialect → E052 ───────

    test("function with non-mysql source_dialect is skipped with E052") {
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

        ddl shouldContain "E052"
        ddl shouldContain "-- TODO: Rewrite function `pg_func` for MySQL (source dialect: postgresql)"

        val note = result.notes.find { it.code == "E052" && it.objectName == "pg_func" }
        note!!.message shouldContain "must be manually rewritten for MySQL"
        result.skippedObjects.any { it.name == "pg_func" } shouldBe true
    }

    // ── 15. Trigger with DELIMITER wrapping ─────────────────────

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

    test("trigger with non-mysql source_dialect is skipped with E052") {
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

        ddl shouldContain "E052"
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

    test("EXCLUDE constraint is not supported and emits E052") {
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

        ddl shouldContain "-- TODO: EXCLUDE constraint `excl_booking_overlap` is not supported in MySQL"
        val note = result.notes.find { it.code == "E052" && it.objectName == "excl_booking_overlap" }
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

    test("procedure with non-mysql source_dialect is skipped with E052") {
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

        ddl shouldContain "E052"
        ddl shouldContain "-- TODO: Rewrite procedure `pg_proc` for MySQL (source dialect: postgresql)"
        result.skippedObjects.any { it.name == "pg_proc" } shouldBe true
    }

    test("view with non-mysql source_dialect is skipped with E052") {
        val schema = emptySchema(
            views = mapOf(
                "pg_view" to ViewDefinition(
                    query = "SELECT * FROM pg_catalog.pg_tables",
                    sourceDialect = "postgresql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E052"
        ddl shouldContain "-- TODO: Rewrite view `pg_view` for MySQL (source dialect: postgresql)"
        result.skippedObjects.any { it.name == "pg_view" } shouldBe true
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

    test("column with string default value renders correctly") {
        val schema = emptySchema(
            tables = mapOf(
                "config" to table(
                    columns = mapOf(
                        "status" to col(
                            NeutralType.Text(maxLength = 20),
                            default = DefaultValue.StringLiteral("active")
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`status` VARCHAR(20) DEFAULT 'active'"
    }

    test("column with numeric default value renders correctly") {
        val schema = emptySchema(
            tables = mapOf(
                "counters" to table(
                    columns = mapOf(
                        "count" to col(
                            NeutralType.Integer,
                            default = DefaultValue.NumberLiteral(0)
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`count` INT DEFAULT 0"
    }

    test("column with function call default renders correctly") {
        val schema = emptySchema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "created_at" to col(
                            NeutralType.DateTime(),
                            default = DefaultValue.FunctionCall("current_timestamp")
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`created_at` DATETIME DEFAULT CURRENT_TIMESTAMP"
    }

    test("multiple columns and primary key in single table") {
        val schema = emptySchema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "email" to col(NeutralType.Email, required = true, unique = true),
                        "name" to col(NeutralType.Text(maxLength = 255), required = true),
                        "age" to col(NeutralType.SmallInt),
                        "balance" to col(NeutralType.Decimal(12, 2), default = DefaultValue.NumberLiteral(0)),
                        "active" to col(NeutralType.BooleanType, default = DefaultValue.BooleanLiteral(true))
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "`id` INT NOT NULL AUTO_INCREMENT"
        ddl shouldContain "`email` VARCHAR(254) NOT NULL UNIQUE"
        ddl shouldContain "`name` VARCHAR(255) NOT NULL"
        ddl shouldContain "`age` SMALLINT"
        ddl shouldContain "`balance` DECIMAL(12,2) DEFAULT 0"
        ddl shouldContain "`active` TINYINT(1) DEFAULT 1"
        ddl shouldContain "PRIMARY KEY (`id`)"
        ddl shouldContain "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"
    }

    test("HASH index with unique flag generates CREATE UNIQUE INDEX USING BTREE") {
        val schema = emptySchema(
            tables = mapOf(
                "cache" to table(
                    columns = mapOf(
                        "hash_key" to col(NeutralType.Text(maxLength = 64))
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_cache_hash",
                            columns = listOf("hash_key"),
                            type = IndexType.HASH,
                            unique = true
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE UNIQUE INDEX `idx_cache_hash` ON `cache` USING BTREE (`hash_key`);"
    }

    test("function with no body emits E052 and is skipped") {
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

        ddl shouldContain "-- TODO: Implement function `stub_func`"
        val note = result.notes.find { it.code == "E052" && it.objectName == "stub_func" }
        note!!.message shouldContain "has no body and must be manually implemented"
        result.skippedObjects.any { it.name == "stub_func" } shouldBe true
    }

    test("trigger with no body emits E052 and is skipped") {
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

        ddl shouldContain "-- TODO: Implement trigger `stub_trg`"
        result.skippedObjects.any { it.name == "stub_trg" } shouldBe true
    }

    test("view with no query is skipped") {
        val schema = emptySchema(
            views = mapOf(
                "empty_view" to ViewDefinition(query = null)
            )
        )

        val result = generator.generate(schema)

        result.skippedObjects.any { it.name == "empty_view" } shouldBe true
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

    test("enum column with ref_type and default value renders correctly") {
        val schema = emptySchema(
            customTypes = mapOf(
                "color_type" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("red", "green", "blue")
                )
            ),
            tables = mapOf(
                "widgets" to table(
                    columns = mapOf(
                        "color" to col(
                            NeutralType.Enum(refType = "color_type"),
                            required = true,
                            default = DefaultValue.StringLiteral("red")
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "ENUM('red', 'green', 'blue')"
        ddl shouldContain "NOT NULL"
        ddl shouldContain "DEFAULT 'red'"
    }

    test("enum column with inline values and unique flag renders correctly") {
        val schema = emptySchema(
            tables = mapOf(
                "metadata" to table(
                    columns = mapOf(
                        "tier" to col(
                            NeutralType.Enum(values = listOf("free", "pro", "enterprise")),
                            unique = true
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "ENUM('free', 'pro', 'enterprise')"
        ddl shouldContain "UNIQUE"
    }

    // ─── Spatial Phase 1 ────────────────────────────────────

    test("geometry point column produces POINT") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        val ddl = result.render()
        ddl shouldContain "POINT"
        ddl shouldContain "SRID"
    }

    listOf(
        "polygon" to "POLYGON",
        "multipolygon" to "MULTIPOLYGON",
        "geometry" to "GEOMETRY",
    ).forEach { (geometryType, expectedSqlType) ->
        test("geometry $geometryType column produces $expectedSqlType") {
            val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
                "shapes" to TableDefinition(columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                    "shape" to ColumnDefinition(type = NeutralType.Geometry(GeometryType(geometryType))),
                ), primaryKey = listOf("id"))
            ))

            val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
            val ddl = result.render()

            ddl shouldContain expectedSqlType
            result.notes.none { it.code == "W120" } shouldBe true
        }
    }

    test("geometry with srid emits W120 warning") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        result.notes.any { it.code == "W120" } shouldBe true
    }

    test("geometry without srid produces no W120") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        result.notes.none { it.code == "W120" } shouldBe true
    }

    test("spatial rollback under native profile drops the table without SQLite-specific statements") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"), srid = 4326)),
                "area" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("polygon"))),
            ), primaryKey = listOf("id"))
        ))

        val ddl = generator.generateRollback(schema, DdlGenerationOptions(SpatialProfile.NATIVE)).render()

        ddl shouldContain "DROP TABLE IF EXISTS `places`;"
        ddl shouldNotContain "AddGeometryColumn"
        ddl shouldNotContain "DiscardGeometryColumn"
    }
})
