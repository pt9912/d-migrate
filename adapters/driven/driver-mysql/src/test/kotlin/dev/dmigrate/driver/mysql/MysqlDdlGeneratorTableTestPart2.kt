package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MysqlDdlGeneratorTableTestPart2 : FunSpec({

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
        ddl shouldContain "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"
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

    test("EXCLUDE constraint is not supported and emits E054") {
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

        ddl.shouldNotContain("-- TODO")
        val note = result.notes.find { it.code == "E054" && it.objectName == "excl_booking_overlap" }
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

    test("header contains schema name, version, and target dialect") {
        val schema = emptySchema()

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- Generated by d-migrate"
        ddl shouldContain "-- Source: neutral schema v1.0 \"test_schema\""
        ddl shouldContain "-- Target: mysql"
    }

    test("malicious table and column names are properly quoted in DDL") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "Robert'; DROP TABLE users; --" to TableDefinition(columns = mapOf(
                "col`inject" to ColumnDefinition(type = NeutralType.Text()),
            ))
        ))
        val rendered = generator.generate(schema).render()
        rendered shouldContain "`Robert'; DROP TABLE users; --`"
        rendered shouldContain "`col``inject`"
    }
})
