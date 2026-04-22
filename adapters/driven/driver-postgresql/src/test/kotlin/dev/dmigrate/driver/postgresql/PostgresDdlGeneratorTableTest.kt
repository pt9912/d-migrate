package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PostgresDdlGeneratorTableTest : FunSpec({

    val generator = PostgresDdlGenerator()

    fun schema(
        name: String = "test_schema",
        version: String = "1.0",
        tables: Map<String, TableDefinition> = emptyMap(),
        customTypes: Map<String, CustomTypeDefinition> = emptyMap(),
        sequences: Map<String, SequenceDefinition> = emptyMap(),
        views: Map<String, ViewDefinition> = emptyMap(),
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap()
    ) = SchemaDefinition(
        name = name,
        version = version,
        tables = tables,
        customTypes = customTypes,
        sequences = sequences,
        views = views,
        functions = functions,
        procedures = procedures,
        triggers = triggers
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

    test("simple table with columns and primary key") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer, required = true),
                        "name" to col(NeutralType.Text(maxLength = 100), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TABLE \"users\""
        ddl shouldContain "\"id\" INTEGER NOT NULL"
        ddl shouldContain "\"name\" VARCHAR(100) NOT NULL"
        ddl shouldContain "PRIMARY KEY (\"id\")"
    }

    test("SERIAL identifier omits NOT NULL") {
        val s = schema(
            tables = mapOf(
                "items" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true), required = true)
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "\"id\" SERIAL"
        ddl shouldNotContain "\"id\" SERIAL NOT NULL"
    }

    test("foreign key reference generates CONSTRAINT FOREIGN KEY REFERENCES") {
        val s = schema(
            tables = mapOf(
                "departments" to table(
                    columns = mapOf("id" to col(NeutralType.Integer)),
                    primaryKey = listOf("id")
                ),
                "employees" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "dept_id" to col(
                            NeutralType.Integer,
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
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"fk_employees_dept_id\" FOREIGN KEY (\"dept_id\") REFERENCES \"departments\" (\"id\")"
        ddl shouldContain "ON DELETE CASCADE"
        ddl shouldContain "ON UPDATE SET NULL"
    }

    test("enum with ref_type generates CREATE TYPE AS ENUM and column uses quoted type name") {
        val s = schema(
            customTypes = mapOf(
                "status_enum" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("active", "inactive", "pending")
                )
            ),
            tables = mapOf(
                "accounts" to table(
                    columns = mapOf(
                        "status" to col(NeutralType.Enum(refType = "status_enum"), required = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TYPE \"status_enum\" AS ENUM ('active', 'inactive', 'pending');"
        ddl shouldContain "\"status\" \"status_enum\" NOT NULL"
    }

    test("enum with inline values generates TEXT with CHECK constraint") {
        val s = schema(
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
        val ddl = generator.generate(s).render()
        ddl shouldContain "\"priority\" TEXT NOT NULL"
        ddl shouldContain "CHECK (\"priority\" IN ('low', 'medium', 'high'))"
    }

    test("composite custom type generates CREATE TYPE AS record") {
        val s = schema(
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
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE TYPE \"address\" AS ("
        ddl shouldContain "\"street\" VARCHAR(200)"
        ddl shouldContain "\"city\" VARCHAR(100)"
    }

    test("domain custom type generates CREATE DOMAIN with CHECK") {
        val s = schema(
            customTypes = mapOf(
                "positive_int" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN,
                    baseType = "INTEGER",
                    check = "VALUE > 0"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE DOMAIN \"positive_int\" AS INTEGER CHECK (VALUE > 0);"
    }

    test("sequence generates CREATE SEQUENCE with all options") {
        val s = schema(
            sequences = mapOf(
                "order_seq" to SequenceDefinition(
                    start = 100,
                    increment = 5,
                    minValue = 1,
                    maxValue = 999999,
                    cycle = true,
                    cache = 10
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE SEQUENCE \"order_seq\""
        ddl shouldContain "START WITH 100"
        ddl shouldContain "INCREMENT BY 5"
        ddl shouldContain "MINVALUE 1"
        ddl shouldContain "MAXVALUE 999999"
        ddl shouldContain " CYCLE"
        ddl shouldContain "CACHE 10"
    }

    test("circular FK generates ALTER TABLE ADD CONSTRAINT") {
        val s = schema(
            tables = mapOf(
                "table_a" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "b_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "table_b", column = "id")
                        )
                    ),
                    primaryKey = listOf("id")
                ),
                "table_b" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "a_id" to col(
                            NeutralType.Integer,
                            references = ReferenceDefinition(table = "table_a", column = "id")
                        )
                    ),
                    primaryKey = listOf("id")
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "ALTER TABLE"
        ddl shouldContain "ADD CONSTRAINT"
        ddl shouldContain "FOREIGN KEY"
        ddl shouldContain "REFERENCES"
    }

    test("partitioning generates PARTITION BY RANGE with sub-partitions") {
        val s = schema(
            tables = mapOf(
                "events" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Integer),
                        "event_date" to col(NeutralType.Date)
                    ),
                    primaryKey = listOf("id", "event_date"),
                    partitioning = PartitionConfig(
                        type = PartitionType.RANGE,
                        key = listOf("event_date"),
                        partitions = listOf(
                            PartitionDefinition(name = "events_2024", from = "'2024-01-01'", to = "'2025-01-01'"),
                            PartitionDefinition(name = "events_2025", from = "'2025-01-01'", to = "'2026-01-01'")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY RANGE (\"event_date\")"
        ddl shouldContain "CREATE TABLE \"events_2024\" PARTITION OF \"events\" FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');"
        ddl shouldContain "CREATE TABLE \"events_2025\" PARTITION OF \"events\" FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');"
    }

    test("check constraint generates CONSTRAINT CHECK") {
        val s = schema(
            tables = mapOf(
                "products" to table(
                    columns = mapOf(
                        "price" to col(NeutralType.Decimal(10, 2))
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
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"chk_price_positive\" CHECK (price > 0)"
    }

    test("unique constraint generates CONSTRAINT UNIQUE") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "first_name" to col(NeutralType.Text(maxLength = 50)),
                        "last_name" to col(NeutralType.Text(maxLength = 50))
                    ),
                    constraints = listOf(
                        ConstraintDefinition(
                            name = "uq_full_name",
                            type = ConstraintType.UNIQUE,
                            columns = listOf("first_name", "last_name")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"uq_full_name\" UNIQUE (\"first_name\", \"last_name\")"
    }

    test("table with DEFAULT string value") {
        val s = schema(
            tables = mapOf(
                "settings" to table(
                    columns = mapOf(
                        "value" to col(NeutralType.Text(), default = DefaultValue.StringLiteral("hello"))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT 'hello'"
    }

    test("table with DEFAULT number value") {
        val s = schema(
            tables = mapOf(
                "counters" to table(
                    columns = mapOf(
                        "count" to col(NeutralType.Integer, default = DefaultValue.NumberLiteral(0))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT 0"
    }

})
