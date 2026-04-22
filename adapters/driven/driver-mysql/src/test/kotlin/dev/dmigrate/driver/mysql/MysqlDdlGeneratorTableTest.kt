package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlDdlGeneratorTableTest : FunSpec({

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

        ddl shouldContain "ALTER TABLE"
        ddl shouldContain "ADD CONSTRAINT"
        ddl shouldContain "FOREIGN KEY"
        ddl shouldContain "REFERENCES"
    }

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

})
