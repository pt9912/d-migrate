package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlDdlGeneratorIndexTest : FunSpec({

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
                            columns = listOf("key").map(::IndexColumn),
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

    test("GIN index is skipped with W102 warning") {
        val schema = emptySchema(
            tables = mapOf(
                "docs" to table(
                    columns = mapOf(
                        "body" to col(NeutralType.Json)
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_docs_body", columns = listOf("body").map(::IndexColumn), type = IndexType.GIN)
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
                        IndexDefinition(name = "idx_geo_loc", columns = listOf("location").map(::IndexColumn), type = IndexType.GIST)
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
                        IndexDefinition(name = "idx_logs_created", columns = listOf("created_at").map(::IndexColumn), type = IndexType.BRIN)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "BRIN index 'idx_logs_created' is not supported in MySQL"
        result.notes.any { it.code == "W102" && it.objectName == "idx_logs_created" } shouldBe true
    }

    test("BTREE index generates CREATE INDEX without warnings") {
        val schema = emptySchema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "sku" to col(NeutralType.Text(maxLength = 50), required = true)
                    ),
                    indices = listOf(
                        IndexDefinition(name = "idx_products_sku", columns = listOf("sku").map(::IndexColumn), type = IndexType.BTREE)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE INDEX `idx_products_sku` ON `products` (`sku`);"
        result.notes.filter { it.objectName == "idx_products_sku" }.shouldBeEmpty()
    }

    test("FULLTEXT index emits CREATE FULLTEXT INDEX over the source columns") {
        val schema = emptySchema(
            tables = mapOf(
                "film" to table(
                    columns = mapOf(
                        "title" to col(NeutralType.Text(maxLength = 255), required = true),
                        "description" to col(NeutralType.Text())
                    ),
                    indices = listOf(
                        IndexDefinition(
                            name = "idx_film_fulltext",
                            columns = listOf("title", "description").map(::IndexColumn),
                            type = IndexType.FULLTEXT,
                            textSearchConfig = "english",
                        )
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE FULLTEXT INDEX `idx_film_fulltext` ON `film` (`title`, `description`);"
        result.notes.filter { it.objectName == "idx_film_fulltext" }.shouldBeEmpty()
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
                            columns = listOf("sku").map(::IndexColumn),
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

    test("partial index is action required and is not rendered") {
        val schema = emptySchema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "email" to col(NeutralType.Email),
                        "deleted_at" to col(NeutralType.DateTime()),
                    ),
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

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E057"
        ddl shouldContain "Partial index 'uq_active_email' is not supported in MySQL and was skipped."
        ddl shouldNotContain "CREATE UNIQUE INDEX `uq_active_email`"
        result.notes.find { it.code == "E057" && it.objectName == "uq_active_email" }!!.type shouldBe
            NoteType.ACTION_REQUIRED
    }

    test("index column directions are rendered") {
        val schema = emptySchema(
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

        val ddl = generator.generate(schema).render()

        ddl shouldContain "CREATE INDEX `idx_orders_created` ON `orders` (`created_at` DESC, `id` ASC);"
    }

    test("unnamed index name collision includes direction suffix") {
        val schema = emptySchema(
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

        val ddl = generator.generate(schema).render()

        ddl shouldContain "CREATE INDEX `idx_orders_created_at_default` ON `orders` (`created_at`);"
        ddl shouldContain "CREATE INDEX `idx_orders_created_at_desc` ON `orders` (`created_at` DESC);"
    }

    test("index without explicit name auto-generates name") {
        val schema = emptySchema(
            tables = mapOf(
                "things" to table(
                    columns = mapOf(
                        "col_a" to col(NeutralType.Integer),
                        "col_b" to col(NeutralType.Integer)
                    ),
                    indices = listOf(
                        IndexDefinition(name = null, columns = listOf("col_a", "col_b").map(::IndexColumn), type = IndexType.BTREE)
                    )
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "CREATE INDEX `idx_things_col_a_col_b` ON `things` (`col_a`, `col_b`);"
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
                            columns = listOf("hash_key").map(::IndexColumn),
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
})
