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

    // ── 7. Composite custom type skipped with E054 ──────────────

    test("composite custom type is skipped with E054 action required note") {
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

        ddl shouldContain "E054"
        ddl shouldContain "Composite type 'address' is not supported in MySQL"

        val notes = result.notes
        val e052 = notes.find { it.code == "E054" && it.objectName == "address" }
        e052 shouldBe TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E054",
            objectName = "address",
            message = "Composite type 'address' is not supported in MySQL and was skipped.",
            hint = "Consider restructuring the data model to avoid composite types."
        )
    }

    // ── 8. Sequence skipped with E056 ───────────────────────────

    test("sequence is skipped with E056 action required note") {
        val schema = emptySchema(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(start = 1, increment = 1)
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "E056"
        ddl shouldContain "Sequence 'order_seq' is not supported in MySQL"

        val notes = result.notes
        val seqNote = notes.find { it.code == "E056" && it.objectName == "order_seq" }
        seqNote shouldBe TransformationNote(
            type = NoteType.ACTION_REQUIRED,
            code = "E056",
            objectName = "order_seq",
            message = "Sequence 'order_seq' is not supported in MySQL without helper_table mode.",
            hint = "Add --mysql-named-sequences helper_table to enable sequence emulation."
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

})
