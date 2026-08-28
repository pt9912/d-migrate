package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
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

    // ADR 0049: SQLite kennt weder abdeckende Indizes noch eine Steuerung der Ablage.

    test("include columns are dropped with W142, not appended to the key") {
        val result = generator.generate(schema(tables = mapOf("t" to table(
            columns = mapOf("id" to col(NeutralType.Integer), "title" to col(NeutralType.Text())),
            indices = listOf(IndexDefinition(
                "ix_cover", listOf(IndexColumn("id")), unique = true, includeColumns = listOf("title"),
            )),
        ))))
        // Angehaengt waere aus `UNIQUE (id)` ein `UNIQUE (id, title)` geworden —
        // eine andere Aussage darueber, welche Zeilen erlaubt sind.
        result.render() shouldContain """CREATE UNIQUE INDEX "ix_cover" ON "t" ("id");"""
        val note = result.notes.single { it.code == "W142" }
        note.message shouldContain "title"
        note.message shouldContain "SQLite"
    }

    test("a clustered index is created as an ordinary one, with W143") {
        val result = generator.generate(schema(tables = mapOf("t" to table(
            columns = mapOf("id" to col(NeutralType.Integer)),
            indices = listOf(IndexDefinition("ix_storage", listOf(IndexColumn("id")), clustered = true)),
        ))))
        result.render() shouldContain """CREATE INDEX "ix_storage" ON "t" ("id");"""
        result.notes.any { it.code == "W143" && it.objectName == "ix_storage" } shouldBe true
    }

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
                            columns = listOf("email").map(::IndexColumn),
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
                            columns = listOf("content").map(::IndexColumn),
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
                            columns = listOf("geom").map(::IndexColumn),
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
                            columns = listOf("ts").map(::IndexColumn),
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
                            columns = listOf("email").map(::IndexColumn),
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
                            columns = listOf("email").map(::IndexColumn),
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

    test("partial unique index appends WHERE predicate") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true)),
                        "email" to col(NeutralType.Email),
                        "deleted_at" to col(NeutralType.DateTime()),
                    ),
                    primaryKey = listOf("id"),
                    indices = listOf(
                        IndexDefinition(
                            name = "uq_active_email",
                            columns = listOf(IndexColumn("email")),
                            unique = true,
                            where = "deleted_at IS NULL",
                        )
                    ),
                )
            )
        )
        val indexSql = generator.generate(s).statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE UNIQUE INDEX") }

        indexSql shouldBe
            "CREATE UNIQUE INDEX \"uq_active_email\" ON \"users\" (\"email\") WHERE deleted_at IS NULL;"
    }

    test("index column directions are rendered") {
        val s = schema(
            tables = mapOf(
                "orders" to table(
                    columns = mapOf(
                        "created_at" to col(NeutralType.DateTime()),
                        "id" to col(NeutralType.Identifier()),
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_orders_created",
                            columns = listOf(
                                IndexColumn("created_at", IndexSortDirection.DESC),
                                IndexColumn("id", IndexSortDirection.ASC),
                            ),
                        )
                    ),
                )
            )
        )

        val indexSql = generator.generate(s).statements
            .map { it.sql.trim() }
            .first { it.startsWith("CREATE INDEX") }

        indexSql shouldBe "CREATE INDEX \"idx_orders_created\" ON \"orders\" (\"created_at\" DESC, \"id\" ASC);"
    }

    test("unnamed index name collision includes direction suffix") {
        val s = schema(
            tables = mapOf(
                "orders" to table(
                    columns = mapOf("created_at" to col(NeutralType.DateTime())),
                    indices = listOf(
                        IndexDefinition(columns = listOf(IndexColumn("created_at"))),
                        IndexDefinition(columns = listOf(IndexColumn("created_at", IndexSortDirection.DESC))),
                    ),
                )
            )
        )

        val indexSql = generator.generate(s).statements.map { it.sql.trim() }

        indexSql shouldContain "CREATE INDEX \"idx_orders_created_at_default\" ON \"orders\" (\"created_at\");"
        indexSql shouldContain "CREATE INDEX \"idx_orders_created_at_desc\" ON \"orders\" (\"created_at\" DESC);"
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
                            columns = listOf("name").map(::IndexColumn),
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
                            columns = listOf("email").map(::IndexColumn),
                            type = IndexType.BTREE,
                            unique = true
                        ),
                        IndexDefinition(
                            name = "idx_users_name",
                            columns = listOf("name").map(::IndexColumn),
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
                            columns = listOf("user_id", "created_at").map(::IndexColumn),
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
