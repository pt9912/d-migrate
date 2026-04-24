package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteDdlGeneratorRollbackTest : FunSpec({

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

    test("rollback generates DROP TABLE statement") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "name" to col(NeutralType.Text(), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val result = generator.generateRollback(s)
        val dropSql = result.statements.map { it.sql.trim() }

        dropSql.any { it.startsWith("DROP TABLE IF EXISTS \"users\"") } shouldBe true
    }

    test("rollback generates DROP VIEW for IF NOT EXISTS views") {
        val s = schema(
            views = mapOf(
                "recent_events" to ViewDefinition(
                    query = "SELECT * FROM events ORDER BY created_at DESC LIMIT 100"
                )
            )
        )
        val result = generator.generateRollback(s)
        val dropSql = result.statements.map { it.sql.trim() }

        dropSql.any { it.startsWith("DROP VIEW IF EXISTS \"recent_events\"") } shouldBe true
    }

    test("rollback generates DROP INDEX for BTREE indices") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "email" to col(NeutralType.Email, required = true)
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_users_email",
                            columns = listOf("email"),
                            type = IndexType.BTREE
                        )
                    )
                )
            )
        )
        val result = generator.generateRollback(s)
        val dropSql = result.statements.map { it.sql.trim() }

        dropSql.any { it.startsWith("DROP INDEX IF EXISTS \"idx_users_email\"") } shouldBe true
    }

    test("rollback generates DROP TRIGGER for sqlite triggers") {
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
                "trg_users_audit" to TriggerDefinition(
                    table = "users",
                    event = TriggerEvent.INSERT,
                    timing = TriggerTiming.AFTER,
                    body = "    INSERT INTO audit_log(action) VALUES ('insert');",
                    sourceDialect = "sqlite"
                )
            )
        )
        val result = generator.generateRollback(s)
        val dropSql = result.statements.map { it.sql.trim() }

        dropSql.any { it.startsWith("DROP TRIGGER IF EXISTS \"trg_users_audit\"") } shouldBe true
    }

    test("rollback statements are in reverse order") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "name" to col(NeutralType.Text())
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_users_name",
                            columns = listOf("name"),
                            type = IndexType.BTREE
                        )
                    )
                )
            )
        )
        val result = generator.generateRollback(s)
        val dropStatements = result.statements.map { it.sql.trim() }

        val dropIndexIdx = dropStatements.indexOfFirst { it.contains("DROP INDEX") }
        val dropTableIdx = dropStatements.indexOfFirst { it.contains("DROP TABLE") }

        (dropIndexIdx < dropTableIdx) shouldBe true
    }
})
