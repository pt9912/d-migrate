package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class PostgresDdlGeneratorTableTestPart2 : FunSpec({

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


    test("table with DEFAULT boolean value") {
        val s = schema(
            tables = mapOf(
                "flags" to table(
                    columns = mapOf(
                        "active" to col(NeutralType.BooleanType, default = DefaultValue.BooleanLiteral(true))
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT TRUE"
    }

    test("table with DEFAULT function call") {
        val s = schema(
            tables = mapOf(
                "records" to table(
                    columns = mapOf(
                        "created_at" to col(
                            NeutralType.DateTime(timezone = true),
                            default = DefaultValue.FunctionCall("current_timestamp")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "DEFAULT CURRENT_TIMESTAMP"
    }

    test("column with NOT NULL and UNIQUE") {
        val s = schema(
            tables = mapOf(
                "users" to table(
                    columns = mapOf(
                        "email" to col(NeutralType.Email, required = true, unique = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "\"email\" VARCHAR(254) NOT NULL"
        ddl shouldContain "UNIQUE"
        val emailLine = ddl.lines().first { it.contains("\"email\"") }
        emailLine shouldContain "NOT NULL"
        emailLine shouldContain "UNIQUE"
    }

    test("SERIAL identifier with UNIQUE modifier") {
        val s = schema(
            tables = mapOf(
                "tokens" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(autoIncrement = true), unique = true)
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val idLine = ddl.lines().first { it.contains("\"id\"") }
        idLine shouldContain "SERIAL"
        idLine shouldContain "UNIQUE"
    }

    test("SERIAL identifier with DEFAULT value") {
        val s = schema(
            tables = mapOf(
                "things" to table(
                    columns = mapOf(
                        "id" to col(
                            NeutralType.Identifier(autoIncrement = true),
                            default = DefaultValue.NumberLiteral(1000)
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val idLine = ddl.lines().first { it.contains("\"id\"") }
        idLine shouldContain "SERIAL"
        idLine shouldContain "DEFAULT 1000"
    }

    test("enum inline values with DEFAULT") {
        val s = schema(
            tables = mapOf(
                "tasks" to table(
                    columns = mapOf(
                        "status" to col(
                            NeutralType.Enum(values = listOf("open", "closed")),
                            default = DefaultValue.StringLiteral("open")
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val statusLine = ddl.lines().first { it.contains("\"status\"") }
        statusLine shouldContain "TEXT"
        statusLine shouldContain "DEFAULT 'open'"
        statusLine shouldContain "CHECK"
    }

    test("enum column with ref_type and UNIQUE") {
        val s = schema(
            customTypes = mapOf(
                "color" to CustomTypeDefinition(
                    kind = CustomTypeKind.ENUM,
                    values = listOf("red", "green", "blue")
                )
            ),
            tables = mapOf(
                "themes" to table(
                    columns = mapOf(
                        "primary_color" to col(
                            NeutralType.Enum(refType = "color"),
                            required = true,
                            unique = true
                        )
                    )
                )
            )
        )
        val ddl = generator.generate(s).render()
        val colorLine = ddl.lines().first { it.contains("\"primary_color\"") }
        colorLine shouldContain "\"color\""
        colorLine shouldContain "NOT NULL"
        colorLine shouldContain "UNIQUE"
    }

    test("domain custom type without CHECK omits CHECK clause") {
        val s = schema(
            customTypes = mapOf(
                "email_type" to CustomTypeDefinition(
                    kind = CustomTypeKind.DOMAIN,
                    baseType = "VARCHAR(254)"
                )
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE DOMAIN \"email_type\" AS VARCHAR(254);"
        ddl shouldNotContain "CHECK"
    }

    test("sequence without optional fields omits MINVALUE MAXVALUE CACHE and includes NO CYCLE") {
        val s = schema(
            sequences = mapOf(
                "simple_seq" to SequenceDefinition(start = 1, increment = 1)
            )
        )
        val ddl = generator.generate(s).render()
        ddl shouldContain "CREATE SEQUENCE \"simple_seq\" START WITH 1 INCREMENT BY 1 NO CYCLE;"
        ddl shouldNotContain "MINVALUE"
        ddl shouldNotContain "MAXVALUE"
        ddl shouldNotContain "CACHE"
    }

    test("EXCLUDE constraint generates CONSTRAINT EXCLUDE") {
        val s = schema(tables = mapOf(
            "rooms" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "range" to col(NeutralType.Text())),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "no_overlap", type = ConstraintType.EXCLUDE,
                    expression = "range WITH &&"
                ))
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"no_overlap\" EXCLUDE (range WITH &&)"
    }

    test("FOREIGN_KEY constraint via constraint table generates FK clause") {
        val s = schema(tables = mapOf(
            "parents" to table(
                columns = mapOf("id" to col(NeutralType.Integer)),
                primaryKey = listOf("id")
            ),
            "children" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "parent_id" to col(NeutralType.Integer)),
                primaryKey = listOf("id"),
                constraints = listOf(ConstraintDefinition(
                    name = "fk_child_parent", type = ConstraintType.FOREIGN_KEY,
                    columns = listOf("parent_id"),
                    references = ConstraintReferenceDefinition(
                        table = "parents", columns = listOf("id"),
                        onDelete = ReferentialAction.CASCADE, onUpdate = ReferentialAction.SET_NULL
                    )
                ))
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "CONSTRAINT \"fk_child_parent\" FOREIGN KEY (\"parent_id\") REFERENCES \"parents\" (\"id\")"
        ddl shouldContain "ON DELETE CASCADE"
        ddl shouldContain "ON UPDATE SET NULL"
    }

    test("LIST partitioning generates FOR VALUES IN") {
        val s = schema(tables = mapOf(
            "events" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "region" to col(NeutralType.Text(50))),
                primaryKey = listOf("id", "region"),
                partitioning = PartitionConfig(
                    type = PartitionType.LIST,
                    key = listOf("region"),
                    partitions = listOf(
                        PartitionDefinition(name = "events_eu", values = listOf("'EU'", "'UK'")),
                        PartitionDefinition(name = "events_us", values = listOf("'US'", "'CA'"))
                    )
                )
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY LIST"
        ddl shouldContain "FOR VALUES IN"
        ddl shouldContain "events_eu"
    }

    test("HASH partitioning generates FOR VALUES WITH") {
        val s = schema(tables = mapOf(
            "logs" to table(
                columns = mapOf("id" to col(NeutralType.Integer), "ts" to col(NeutralType.DateTime())),
                primaryKey = listOf("id", "ts"),
                partitioning = PartitionConfig(
                    type = PartitionType.HASH,
                    key = listOf("id"),
                    partitions = listOf(
                        PartitionDefinition(name = "logs_p0", from = "MODULUS 4, REMAINDER 0"),
                        PartitionDefinition(name = "logs_p1", from = "MODULUS 4, REMAINDER 1")
                    )
                )
            )
        ))
        val ddl = generator.generate(s).render()
        ddl shouldContain "PARTITION BY HASH"
        ddl shouldContain "FOR VALUES WITH"
        ddl shouldContain "logs_p0"
    }

    test("uuid json binary and array retain their PostgreSQL mappings") {
        val s = schema(
            tables = mapOf(
                "typed" to table(
                    columns = mapOf(
                        "id" to col(NeutralType.Identifier(true)),
                        "external_id" to col(NeutralType.Uuid),
                        "payload" to col(NeutralType.Json),
                        "raw_data" to col(NeutralType.Binary),
                        "tags" to col(NeutralType.Array("text")),
                    ),
                    primaryKey = listOf("id")
                )
            )
        )

        val ddl = generator.generate(s).render()

        ddl shouldContain "\"external_id\" UUID"
        ddl shouldContain "\"payload\" JSONB"
        ddl shouldContain "\"raw_data\" BYTEA"
        ddl shouldContain "\"tags\" TEXT[]"
    }

    test("array with integer element type") {
        val s = schema(tables = mapOf("t" to table(
            columns = mapOf("ids" to col(NeutralType.Array("integer")))
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "INTEGER[]"
    }

    test("array with boolean element type") {
        val s = schema(tables = mapOf("t" to table(
            columns = mapOf("flags" to col(NeutralType.Array("boolean")))
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "BOOLEAN[]"
    }

    test("array with uuid element type") {
        val s = schema(tables = mapOf("t" to table(
            columns = mapOf("refs" to col(NeutralType.Array("uuid")))
        )))
        val ddl = generator.generate(s).render()
        ddl shouldContain "UUID[]"
    }

    test("malicious table and column names are properly quoted in DDL") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "Robert'; DROP TABLE users; --" to TableDefinition(columns = mapOf(
                "col\"inject" to ColumnDefinition(type = NeutralType.Text()),
            ))
        ))
        val rendered = generator.generate(schema).render()
        rendered shouldContain "\"Robert'; DROP TABLE users; --\""
        rendered shouldContain "\"col\"\"inject\""
    }
})
