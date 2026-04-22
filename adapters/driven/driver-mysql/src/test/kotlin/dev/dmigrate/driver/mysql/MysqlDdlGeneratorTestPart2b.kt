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

class MysqlDdlGeneratorTestPart2b : FunSpec({

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

        ddl.shouldNotContain("-- TODO")

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

    // ── 14. Function with different source_dialect → E053 ───────

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
        ddl shouldContain "-- TODO: Rewrite function `pg_func` for MySQL (source dialect: postgresql)"

        val note = result.notes.find { it.code == "E053" && it.objectName == "pg_func" }
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

})
