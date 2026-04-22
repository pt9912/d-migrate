package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqliteDdlGeneratorIndexTest : FunSpec({

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

    test("HASH index emits W102 warning and is skipped") {
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
                            type = IndexType.HASH
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val notes = result.notes

        notes.any { it.code == "W102" && it.objectName == "idx_users_email" } shouldBe true
        result.statements.none {
            it.sql.trim().startsWith("CREATE INDEX \"idx_users_email\"") ||
                it.sql.trim().startsWith("CREATE UNIQUE INDEX \"idx_users_email\"")
        } shouldBe true
    }

    test("GIN index emits W102 warning and is skipped") {
        val s = schema(
            tables = mapOf(
                "docs" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "content" to col(NeutralType.Json)
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_docs_content",
                            columns = listOf("content"),
                            type = IndexType.GIN
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)

        result.notes.any { it.code == "W102" && it.message.contains("GIN") } shouldBe true
    }

    test("GIST index emits W102 warning and is skipped") {
        val s = schema(
            tables = mapOf(
                "locations" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "geom" to col(NeutralType.Text())
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_locations_geom",
                            columns = listOf("geom"),
                            type = IndexType.GIST
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)

        result.notes.any { it.code == "W102" && it.message.contains("GIST") } shouldBe true
    }

    test("BRIN index emits W102 warning and is skipped") {
        val s = schema(
            tables = mapOf(
                "logs" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "ts" to col(NeutralType.DateTime())
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_logs_ts",
                            columns = listOf("ts"),
                            type = IndexType.BRIN
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)

        result.notes.any { it.code == "W102" && it.message.contains("BRIN") } shouldBe true
    }

    test("BTREE index generates CREATE INDEX statement") {
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
        val result = generator.generate(s)
        val indexSql = result.statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE INDEX") }

        indexSql shouldBe "CREATE INDEX \"idx_users_email\" ON \"users\" (\"email\");"
    }

    test("BTREE unique index generates CREATE UNIQUE INDEX") {
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
                            name = "idx_users_email_uniq",
                            columns = listOf("email"),
                            type = IndexType.BTREE,
                            unique = true
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val indexSql = result.statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE UNIQUE INDEX") }

        indexSql shouldBe "CREATE UNIQUE INDEX \"idx_users_email_uniq\" ON \"users\" (\"email\");"
    }

    test("index without explicit name gets auto-generated name") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "name" to col(NeutralType.Text(), required = true)
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            columns = listOf("name"),
                            type = IndexType.BTREE
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val indexSql = result.statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE INDEX") }

        indexSql shouldContain "idx_users_name"
    }

    test("multiple indices on same table are all generated") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "email" to col(NeutralType.Email, required = true),
                        "name" to col(NeutralType.Text(), required = true)
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_users_email",
                            columns = listOf("email"),
                            type = IndexType.BTREE,
                            unique = true
                        ),
                        IndexDefinition(
                            name = "idx_users_name",
                            columns = listOf("name"),
                            type = IndexType.BTREE
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val indexStatements = result.statements
            .map { it.sql.trim() }
            .filter { it.startsWith("CREATE INDEX") || it.startsWith("CREATE UNIQUE INDEX") }

        indexStatements shouldHaveSize 2
    }

    test("multi-column BTREE index generates correct SQL") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "user_id" to col(NeutralType.Integer, required = true),
                        "created_at" to col(NeutralType.DateTime(), required = true)
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_events_user_date",
                            columns = listOf("user_id", "created_at"),
                            type = IndexType.BTREE
                        )
                    )
                )
            )
        )
        val result = generator.generate(s)
        val indexSql = result.statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE INDEX") }

        indexSql shouldBe "CREATE INDEX \"idx_events_user_date\" ON \"events\" (\"user_id\", \"created_at\");"
    }
})
