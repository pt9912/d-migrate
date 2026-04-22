package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteDdlGeneratorRoutineTest : FunSpec({

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

    test("function is skipped with E054 and added to skippedObjects") {
        val s = schema(
            functions = mapOf(
                "calculate_total" to FunctionDefinition(
                    body = "RETURN a + b;",
                    language = "sql"
                )
            )
        )
        val result = generator.generate(s)

        result.skippedObjects.any { it.type == "function" && it.name == "calculate_total" } shouldBe true
        result.notes.any { it.code == "E054" && it.objectName == "calculate_total" } shouldBe true
    }

    test("procedure is skipped with E054 and added to skippedObjects") {
        val s = schema(
            procedures = mapOf(
                "cleanup_old_data" to ProcedureDefinition(
                    body = "DELETE FROM logs WHERE created_at < date('now', '-30 days');",
                    language = "sql"
                )
            )
        )
        val result = generator.generate(s)

        result.skippedObjects.any { it.type == "procedure" && it.name == "cleanup_old_data" } shouldBe true
        result.notes.any { it.code == "E054" && it.objectName == "cleanup_old_data" } shouldBe true
    }

    test("trigger with sqlite source dialect produces CREATE TRIGGER with BEGIN...END") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "updated_at" to col(NeutralType.DateTime())
                    ),
                    primaryKey = listOf("id")
                )
            ),
            triggers = mapOf(
                "trg_users_updated" to TriggerDefinition(
                    table = "users",
                    event = TriggerEvent.UPDATE,
                    timing = TriggerTiming.AFTER,
                    forEach = TriggerForEach.ROW,
                    body = "    UPDATE users SET updated_at = datetime('now') WHERE id = NEW.id;",
                    sourceDialect = "sqlite"
                )
            )
        )
        val result = generator.generate(s)
        val triggerSql = result.statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE TRIGGER") }

        triggerSql shouldContain "CREATE TRIGGER \"trg_users_updated\""
        triggerSql shouldContain "AFTER UPDATE ON \"users\""
        triggerSql shouldContain "FOR EACH ROW"
        triggerSql shouldContain "BEGIN"
        triggerSql shouldContain "END;"
    }

    test("trigger with null source dialect produces CREATE TRIGGER") {
        val s = schema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                )
            ),
            triggers = mapOf(
                "trg_items_insert" to TriggerDefinition(
                    table = "items",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.BEFORE,
                    forEach = TriggerForEach.ROW,
                    condition = "NEW.id > 0",
                    body = "    SELECT RAISE(ABORT, 'invalid id');"
                )
            )
        )
        val result = generator.generate(s)
        val triggerSql = result.statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE TRIGGER") }

        triggerSql shouldContain "CREATE TRIGGER \"trg_items_insert\""
        triggerSql shouldContain "BEFORE INSERT ON \"items\""
        triggerSql shouldContain "WHEN NEW.id > 0"
        triggerSql shouldContain "BEGIN"
        triggerSql shouldContain "END;"
    }

    test("trigger with non-sqlite source dialect is skipped with E053") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                )
            ),
            triggers = mapOf(
                "trg_pg_notify" to TriggerDefinition(
                    table = "users",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    body = "PERFORM pg_notify('user_created', NEW.id::text);",
                    sourceDialect = "postgresql"
                )
            )
        )
        val result = generator.generate(s)

        result.skippedObjects.any { it.type == "trigger" && it.name == "trg_pg_notify" } shouldBe true
        result.notes.any { it.code == "E053" && it.objectName == "trg_pg_notify" } shouldBe true
    }

    test("trigger with no body is skipped") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true))
                    ),
                    primaryKey = listOf("id")
                )
            ),
            triggers = mapOf(
                "trg_empty" to TriggerDefinition(
                    table = "users",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    body = null
                )
            )
        )
        val result = generator.generate(s)

        result.skippedObjects.any { it.type == "trigger" && it.name == "trg_empty" } shouldBe true
        result.notes.any { it.code == "E053" && it.objectName == "trg_empty" } shouldBe true
    }
})
