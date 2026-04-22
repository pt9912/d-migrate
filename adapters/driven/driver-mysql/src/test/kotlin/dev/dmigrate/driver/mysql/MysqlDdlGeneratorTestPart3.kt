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

class MysqlDdlGeneratorTestPart3 : FunSpec({

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
                            columns = listOf("hash_key"),
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

    test("function with no body emits E053 and is skipped") {
        val schema = emptySchema(
            functions = mapOf(
                "stub_func" to FunctionDefinition(
                    body = null,
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- TODO: Implement function `stub_func`"
        val note = result.notes.find { it.code == "E053" && it.objectName == "stub_func" }
        note!!.message shouldContain "has no body and must be manually implemented"
        result.skippedObjects.any { it.name == "stub_func" } shouldBe true
    }

    test("trigger with no body emits E053 and is skipped") {
        val schema = emptySchema(
            triggers = mapOf(
                "stub_trg" to TriggerDefinition(
                    table = "events",
                    event = TriggerEvent.DELETE,
                    timing = TriggerTiming.BEFORE,
                    body = null
                )
            )
        )

        val result = generator.generate(schema)
        val ddl = result.render()

        ddl shouldContain "-- TODO: Implement trigger `stub_trg`"
        val note = result.notes.find { it.code == "E053" && it.objectName == "stub_trg" }
        note!!.message shouldContain "has no body and must be manually implemented"
        result.skippedObjects.any { it.name == "stub_trg" } shouldBe true
    }

    test("view with no query is skipped") {
        val schema = emptySchema(
            views = mapOf(
                "empty_view" to ViewDefinition(query = null)
            )
        )

        val result = generator.generate(schema)

        result.skippedObjects.any { it.name == "empty_view" } shouldBe true
    }

    test("rollback generates DROP PROCEDURE for delimiter-wrapped procedures") {
        val schema = emptySchema(
            procedures = mapOf(
                "my_proc" to ProcedureDefinition(
                    body = "    SELECT 1;",
                    sourceDialect = "mysql"
                )
            )
        )

        val result = generator.generateRollback(schema)
        val ddl = result.render()

        ddl shouldContain "DROP PROCEDURE IF EXISTS `my_proc`;"
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

    // ─── Spatial Phase 1 ────────────────────────────────────

    test("geometry point column produces POINT") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        val ddl = result.render()
        ddl shouldContain "POINT"
        ddl shouldContain "SRID"
    }

    listOf(
        "polygon" to "POLYGON",
        "multipolygon" to "MULTIPOLYGON",
        "geometry" to "GEOMETRY",
    ).forEach { (geometryType, expectedSqlType) ->
        test("geometry $geometryType column produces $expectedSqlType") {
            val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
                "shapes" to TableDefinition(columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                    "shape" to ColumnDefinition(type = NeutralType.Geometry(GeometryType(geometryType))),
                ), primaryKey = listOf("id"))
            ))

            val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
            val ddl = result.render()

            ddl shouldContain expectedSqlType
            result.notes.none { it.code == "W120" } shouldBe true
        }
    }

    test("geometry with srid emits W120 warning") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry(
                    GeometryType("point"), srid = 4326)),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        result.notes.any { it.code == "W120" } shouldBe true
    }

    test("geometry without srid produces no W120") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "t" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "loc" to ColumnDefinition(type = NeutralType.Geometry()),
            ), primaryKey = listOf("id"))
        ))
        val result = generator.generate(schema, DdlGenerationOptions(SpatialProfile.NATIVE))
        result.notes.none { it.code == "W120" } shouldBe true
    }

    test("spatial rollback under native profile drops the table without SQLite-specific statements") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "places" to TableDefinition(columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(true)),
                "location" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("point"), srid = 4326)),
                "area" to ColumnDefinition(type = NeutralType.Geometry(GeometryType("polygon"))),
            ), primaryKey = listOf("id"))
        ))

        val ddl = generator.generateRollback(schema, DdlGenerationOptions(SpatialProfile.NATIVE)).render()

        ddl shouldContain "DROP TABLE IF EXISTS `places`;"
        ddl shouldNotContain "AddGeometryColumn"
        ddl shouldNotContain "DiscardGeometryColumn"
    }

    // ── security: malicious identifiers are quoted ─

    test("malicious table and column names are properly quoted in DDL") {
        val schema = SchemaDefinition(name = "T", version = "1", tables = mapOf(
            "Robert'; DROP TABLE users; --" to TableDefinition(columns = mapOf(
                "col`inject" to ColumnDefinition(type = NeutralType.Text()),
            ))
        ))
        val rendered = generator.generate(schema).render()
        // Table name is safely quoted — injection payload neutralized
        rendered shouldContain "`Robert'; DROP TABLE users; --`"
        // Embedded backtick is escaped as ``
        rendered shouldContain "`col``inject`"
    }

    // ── helper_table mode (0.9.3 AP 6.4) ───────────────────────

    val helperOpts = DdlGenerationOptions(
        mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE,
    )

    fun seqSchema(
        seqName: String = "invoice_seq",
        tableName: String = "invoices",
        colName: String = "invoice_number",
        cache: Int? = null,
    ) = SchemaDefinition(
        name = "SeqTest", version = "1",
        sequences = mapOf(seqName to SequenceDefinition(start = 1000, increment = 1, cache = cache)),
        tables = mapOf(tableName to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                colName to ColumnDefinition(
                    type = NeutralType.Integer,
                    default = DefaultValue.SequenceNextVal(seqName),
                ),
            ),
            primaryKey = listOf("id"),
        )),
    )

    test("helper_table generates dmg_sequences table and seed") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        ddl shouldContain "CREATE TABLE `dmg_sequences`"
        ddl shouldContain "INSERT INTO `dmg_sequences`"
        ddl shouldContain "'invoice_seq'"
        ddl shouldContain "1000" // start value
    }

    test("helper_table generates dmg_nextval and dmg_setval routines") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        ddl shouldContain "CREATE FUNCTION `dmg_nextval`"
        ddl shouldContain "CREATE FUNCTION `dmg_setval`"
        ddl shouldContain "DELIMITER //"
    }

    test("helper_table generates BEFORE INSERT trigger for SequenceNextVal column") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        ddl shouldContain "BEFORE INSERT ON `invoices`"
        ddl shouldContain "dmg_nextval"
        ddl shouldContain "invoice_seq"
        ddl shouldContain "NEW.`invoice_number`"
    }

    test("helper_table column with SequenceNextVal has no DEFAULT clause on that column") {
        val result = generator.generate(seqSchema(), helperOpts)
        val ddl = result.render()
        // The sequence-backed column should NOT have DEFAULT — trigger handles it
        // Extract just the invoice_number column line
        val colLine = ddl.lines().firstOrNull { it.contains("`invoice_number`") }
        colLine shouldNotBe null
        colLine!! shouldNotContain "DEFAULT"
    }

    test("helper_table emits W115 per SequenceNextVal column") {
        val result = generator.generate(seqSchema(), helperOpts)
        result.notes.any { it.code == "W115" && it.objectName == "invoices.invoice_number" } shouldBe true
    }

    test("helper_table emits W117 global warning") {
        val result = generator.generate(seqSchema(), helperOpts)
        result.globalNotes.any { it.code == "W117" } shouldBe true
    }

    test("helper_table emits W114 when sequence has cache") {
        val result = generator.generate(seqSchema(cache = 20), helperOpts)
        result.notes.any { it.code == "W114" && it.objectName == "invoice_seq" } shouldBe true
    }

    test("action_required + SequenceNextVal emits E056 with helper_table hint") {
        val result = generator.generate(seqSchema())
        val notes = result.notes
        val e056 = notes.filter { it.code == "E056" }
        e056.any { it.objectName == "invoices.invoice_number" && it.hint?.contains("helper_table") == true } shouldBe true
    }

    test("helper_table rollback drops in correct order: triggers → routines → tables") {
        val rollback = generator.generateRollback(seqSchema(), helperOpts)
        val ddl = rollback.render()
        ddl shouldContain "DROP"
        rollback.statements.size shouldNotBe 0
        // Verify ordering: trigger drops appear before function drops,
        // function drops before dmg_sequences table drop
        val lines = ddl.lines()
        val triggerDropIdx = lines.indexOfFirst { it.contains("DROP TRIGGER") }
        val funcDropIdx = lines.indexOfFirst { it.contains("DROP FUNCTION") }
        val seqTableDropIdx = lines.indexOfFirst { it.contains("dmg_sequences") && it.contains("DROP") }
        if (triggerDropIdx >= 0 && funcDropIdx >= 0) {
            (triggerDropIdx < funcDropIdx) shouldBe true
        }
        if (funcDropIdx >= 0 && seqTableDropIdx >= 0) {
            (funcDropIdx < seqTableDropIdx) shouldBe true
        }
    }

    test("helper_table phases: dmg_sequences in PRE_DATA, routines and triggers in POST_DATA") {
        val result = generator.generate(seqSchema(), helperOpts)
        val preData = result.renderPhase(dev.dmigrate.driver.DdlPhase.PRE_DATA)
        val postData = result.renderPhase(dev.dmigrate.driver.DdlPhase.POST_DATA)
        preData shouldContain "CREATE TABLE `dmg_sequences`"
        postData shouldContain "CREATE FUNCTION `dmg_nextval`"
        postData shouldContain "BEFORE INSERT"
    }

    test("E124: helper_table with schema containing dmg_sequences table emits collision") {
        val schema = SchemaDefinition(
            name = "Collision", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf(
                "dmg_sequences" to TableDefinition(
                    columns = linkedMapOf("id" to ColumnDefinition(type = NeutralType.Integer)),
                    primaryKey = listOf("id"),
                ),
            ),
        )
        val result = generator.generate(schema, helperOpts)
        result.skippedObjects.any { it.code == "E124" && it.name == "dmg_sequences" } shouldBe true
    }

    test("E124: helper_table with schema containing dmg_nextval function emits collision") {
        val schema = SchemaDefinition(
            name = "FnCollision", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("t" to TableDefinition(
                columns = linkedMapOf("id" to ColumnDefinition(type = NeutralType.Integer)),
                primaryKey = listOf("id"),
            )),
            functions = mapOf("dmg_nextval" to FunctionDefinition(
                returns = ReturnType("BIGINT"), language = "SQL",
            )),
        )
        val result = generator.generate(schema, helperOpts)
        result.skippedObjects.any { it.code == "E124" && it.name == "dmg_nextval" } shouldBe true
    }

    test("E124: helper_table with trigger name collision emits collision for trigger") {
        // Create a schema where a user trigger has the same name as the canonical support trigger
        val trigName = MysqlSequenceNaming.triggerName("invoices", "invoice_number")
        val schema = SchemaDefinition(
            name = "TrigCollision", version = "1",
            sequences = mapOf("my_seq" to SequenceDefinition(start = 1)),
            tables = mapOf("invoices" to TableDefinition(
                columns = linkedMapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                    "invoice_number" to ColumnDefinition(
                        type = NeutralType.Integer,
                        default = DefaultValue.SequenceNextVal("my_seq"),
                    ),
                ),
                primaryKey = listOf("id"),
            )),
            triggers = mapOf(trigName to TriggerDefinition(
                table = "invoices", event = TriggerEvent.INSERT, timing = TriggerTiming.BEFORE,
            )),
        )
        val result = generator.generate(schema, helperOpts)
        result.skippedObjects.any { it.code == "E124" && it.name == trigName } shouldBe true
    }

    test("helper_table rollback contains DROP TRIGGER for support triggers") {
        val rollback = generator.generateRollback(seqSchema(), helperOpts)
        val ddl = rollback.render()
        ddl shouldContain "DROP TRIGGER"
        ddl shouldContain "DROP FUNCTION"
    }
})
