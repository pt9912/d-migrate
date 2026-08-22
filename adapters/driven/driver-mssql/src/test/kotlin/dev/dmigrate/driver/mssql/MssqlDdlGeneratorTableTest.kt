package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.codes
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.col
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.idTable
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.notesWithCode
import dev.dmigrate.driver.mssql.MssqlDdlTestSupport.schema
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class MssqlDdlGeneratorTableTest : FunSpec({

    val generator = MssqlDdlGenerator()

    test("minimal table: identity PK, NVARCHAR, named primary key, bracket quoting") {
        val ddl = generator.generate(
            schema(tables = mapOf("users" to idTable("name" to col(NeutralType.Text(100), required = true)))),
        ).render()
        ddl shouldContain "CREATE TABLE [users] (\n" +
            "    [id] INT IDENTITY(1,1) NOT NULL,\n" +
            "    [name] NVARCHAR(100) NOT NULL,\n" +
            "    CONSTRAINT [pk_users] PRIMARY KEY ([id])\n" +
            ");"
        ddl shouldContain "-- Target: mssql"
    }

    test("defaults and unique are named constraints; Unicode literals; booleans as bits") {
        val table = idTable(
            "email" to col(NeutralType.Text(254), required = true, unique = true),
            "active" to col(NeutralType.BooleanType, required = true, default = DefaultValue.BooleanLiteral(true)),
            "nick" to col(NeutralType.Text(50), default = DefaultValue.StringLiteral("n/a")),
            "created_at" to col(NeutralType.DateTime(timezone = true), default = DefaultValue.FunctionCall("current_timestamp")),
            "token" to col(NeutralType.Uuid, default = DefaultValue.FunctionCall("gen_uuid")),
        )
        val ddl = generator.generate(schema(tables = mapOf("t" to table))).render()
        ddl shouldContain "[email] NVARCHAR(254) NOT NULL CONSTRAINT [uq_t_email] UNIQUE"
        ddl shouldContain "[active] BIT NOT NULL CONSTRAINT [df_t_active] DEFAULT 1"
        ddl shouldContain "[nick] NVARCHAR(50) CONSTRAINT [df_t_nick] DEFAULT N'n/a'"
        ddl shouldContain "[created_at] DATETIMEOFFSET CONSTRAINT [df_t_created_at] DEFAULT SYSDATETIMEOFFSET()"
        ddl shouldContain "[token] UNIQUEIDENTIFIER CONSTRAINT [df_t_token] DEFAULT NEWID()"
    }

    test("nullable UNIQUE column emits W138 (single-NULL semantics), NOT NULL UNIQUE does not") {
        val result = generator.generate(
            schema(
                tables = mapOf(
                    "t" to idTable(
                        "a" to col(NeutralType.Text(10), unique = true),
                        "b" to col(NeutralType.Text(10), required = true, unique = true),
                    ),
                ),
            ),
        )
        val w138 = result.notesWithCode("W138")
        w138.map { it.objectName } shouldBe listOf("t.a")
        w138.single().type shouldBe NoteType.WARNING
    }

    test("table-level UNIQUE over a nullable column emits W138 with the constraint name") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "a" to col(NeutralType.Integer),
                "b" to col(NeutralType.Integer, required = true),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(ConstraintDefinition("uq_ab", ConstraintType.UNIQUE, columns = listOf("a", "b"))),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldContain "CONSTRAINT [uq_ab] UNIQUE ([a], [b])"
        result.notesWithCode("W138").single().objectName shouldBe "t.uq_ab"
    }

    test("identity generation renders IDENTITY(1,1); BY DEFAULT and defaults on identity surface as W140") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.BigInteger, generation = ColumnGeneration.Identity(IdentityMode.ALWAYS)),
                "seq" to col(NeutralType.Integer, generation = ColumnGeneration.Identity(IdentityMode.BY_DEFAULT)),
                "bad" to col(
                    NeutralType.Identifier(autoIncrement = true),
                    default = DefaultValue.NumberLiteral(7),
                ),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        val ddl = result.render()
        ddl shouldContain "[id] BIGINT IDENTITY(1,1) NOT NULL"
        ddl shouldContain "[seq] INT IDENTITY(1,1) NOT NULL"
        ddl shouldContain "[bad] INT IDENTITY(1,1) NOT NULL"
        ddl shouldNotContain "DEFAULT 7"
        result.notesWithCode("W140").map { it.objectName } shouldBe listOf("t.seq", "t.bad")
    }

    test("decimal identity keeps its type; identity on a scaled decimal falls back to a plain column") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.Decimal(18, 0), generation = ColumnGeneration.Identity(IdentityMode.ALWAYS)),
                "x" to col(NeutralType.Decimal(10, 2), generation = ColumnGeneration.Identity(IdentityMode.ALWAYS)),
            ),
            primaryKey = listOf("id"),
        )
        val ddl = generator.generate(schema(tables = mapOf("t" to table))).render()
        ddl shouldContain "[id] DECIMAL(18,0) IDENTITY(1,1) NOT NULL"
        ddl shouldContain "[x] DECIMAL(10,2),"
    }

    test("type degradations carry their warning codes") {
        val table = idTable(
            "wide" to col(NeutralType.Text(5000)),
            "doc" to col(NeutralType.Json),
            "tags" to col(NeutralType.Array("text")),
            "vec" to col(NeutralType.FullText),
            "big" to col(NeutralType.Decimal(60, 4)),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        val ddl = result.render()
        ddl shouldContain "[wide] NVARCHAR(MAX)"
        ddl shouldContain "[doc] NVARCHAR(MAX)"
        ddl shouldContain "[tags] NVARCHAR(MAX)"
        ddl shouldContain "[vec] NVARCHAR(MAX)"
        ddl shouldContain "[big] DECIMAL(38,4)"
        result.notesWithCode("W136").map { it.objectName } shouldBe listOf("t.wide")
        result.notesWithCode("W137").map { it.objectName } shouldBe listOf("t.doc", "t.tags")
        result.notesWithCode("W132").map { it.objectName } shouldBe listOf("t.vec")
        result.notesWithCode("W139").map { it.objectName } shouldBe listOf("t.big")
    }

    test("inline enum renders bounded NVARCHAR with a named CHECK; ref_type enum resolves the custom type") {
        val customTypes = mapOf(
            "order_status" to CustomTypeDefinition(CustomTypeKind.ENUM, values = listOf("pending", "shipped")),
        )
        val table = idTable(
            "status" to col(
                NeutralType.Enum(refType = "order_status"),
                default = DefaultValue.StringLiteral("pending"),
            ),
            "size" to col(NeutralType.Enum(values = listOf("s", "m", "xl")), required = true),
        )
        val result = generator.generate(schema(tables = mapOf("orders" to table), customTypes = customTypes))
        val ddl = result.render()
        ddl shouldContain "[status] NVARCHAR(7) CONSTRAINT [df_orders_status] DEFAULT N'pending' " +
            "CONSTRAINT [ck_orders_status] CHECK ([status] IN (N'pending', N'shipped'))"
        ddl shouldContain "[size] NVARCHAR(2) NOT NULL CONSTRAINT [ck_orders_size] CHECK ([size] IN (N's', N'm', N'xl'))"
        ddl shouldNotContain "CREATE TYPE"
    }

    test("domain custom type renders base type + CHECK with VALUE bound to the column") {
        val customTypes = mapOf(
            "positive_amount" to CustomTypeDefinition(
                CustomTypeKind.DOMAIN, baseType = "decimal", precision = 10, scale = 2, check = "VALUE >= 0",
            ),
            "label" to CustomTypeDefinition(CustomTypeKind.DOMAIN, baseType = "VARCHAR(20)"),
        )
        val table = idTable(
            "amount" to col(NeutralType.Enum(refType = "positive_amount"), required = true),
            "lbl" to col(NeutralType.Enum(refType = "label")),
        )
        val ddl = generator.generate(schema(tables = mapOf("t" to table), customTypes = customTypes)).render()
        ddl shouldContain "[amount] DECIMAL(10,2) NOT NULL CONSTRAINT [ck_t_amount] CHECK ([amount] >= 0)"
        ddl shouldContain "[lbl] NVARCHAR(20)"
    }

    test("enum without resolvable values falls back to NVARCHAR(MAX)") {
        val table = idTable("e" to col(NeutralType.Enum(refType = "missing")))
        generator.generate(schema(tables = mapOf("t" to table))).render() shouldContain "[e] NVARCHAR(MAX)"
    }

    test("composite custom type is skipped with E054; EXCLUDE constraint is skipped with E054") {
        val table = TableDefinition(
            columns = linkedMapOf("id" to col(NeutralType.Identifier(autoIncrement = true))),
            primaryKey = listOf("id"),
            constraints = listOf(ConstraintDefinition("ex", ConstraintType.EXCLUDE, expression = "id WITH =")),
        )
        val result = generator.generate(
            schema(
                tables = mapOf("t" to table),
                customTypes = mapOf("address" to CustomTypeDefinition(CustomTypeKind.COMPOSITE, fields = emptyMap())),
            ),
        )
        result.notesWithCode("E054").map { it.objectName } shouldBe listOf("address", "ex")
        result.statements.joinToString("\n") { it.sql } shouldNotContain "EXCLUDE"
    }

    test("foreign keys: inline column refs, explicit constraints, RESTRICT renders as NO ACTION") {
        val orders = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "customer_id" to col(
                    NeutralType.Integer, required = true,
                    references = ReferenceDefinition("customers", "id", onDelete = ReferentialAction.RESTRICT),
                ),
                "ref_id" to col(NeutralType.Integer),
            ),
            primaryKey = listOf("id"),
            constraints = listOf(
                ConstraintDefinition(
                    "fk_ref", ConstraintType.FOREIGN_KEY, columns = listOf("ref_id"),
                    references = ConstraintReferenceDefinition(
                        "customers", listOf("id"), onDelete = ReferentialAction.SET_NULL,
                        onUpdate = ReferentialAction.CASCADE,
                    ),
                ),
                ConstraintDefinition("chk_ref", ConstraintType.CHECK, expression = "ref_id > 0"),
            ),
        )
        val ddl = generator.generate(
            schema(tables = mapOf("customers" to idTable(), "orders" to orders)),
        ).render()
        ddl shouldContain "CONSTRAINT [fk_orders_customer_id] FOREIGN KEY ([customer_id]) " +
            "REFERENCES [customers] ([id]) ON DELETE NO ACTION"
        ddl shouldContain "CONSTRAINT [fk_ref] FOREIGN KEY ([ref_id]) REFERENCES [customers] ([id]) " +
            "ON DELETE SET NULL ON UPDATE CASCADE"
        ddl shouldContain "CONSTRAINT [chk_ref] CHECK (ref_id > 0)"
        ddl shouldNotContain "RESTRICT"
        // customers is created before orders (topological order)
        (ddl.indexOf("CREATE TABLE [customers]") < ddl.indexOf("CREATE TABLE [orders]")) shouldBe true
    }

    test("circular references are deferred to ALTER TABLE ADD CONSTRAINT") {
        val a = idTable("b_id" to col(NeutralType.Integer, references = ReferenceDefinition("b", "id")))
        val b = idTable("a_id" to col(NeutralType.Integer, references = ReferenceDefinition("a", "id")))
        val ddl = generator.generate(schema(tables = mapOf("a" to a, "b" to b))).render()
        ddl shouldContain "ALTER TABLE [b] ADD CONSTRAINT [fk_b_a_id] FOREIGN KEY ([a_id]) REFERENCES [a] ([id]);"
    }

    test("deferForeignKeys moves every FK into POST_DATA ALTER TABLE statements") {
        val orders = idTable(
            "customer_id" to col(NeutralType.Integer, references = ReferenceDefinition("customers", "id")),
        )
        val result = generator.generate(
            schema(tables = mapOf("customers" to idTable(), "orders" to orders)),
            DdlGenerationOptions(deferForeignKeys = true),
        )
        val pre = result.renderPhase(dev.dmigrate.driver.DdlPhase.PRE_DATA)
        val post = result.renderPhase(dev.dmigrate.driver.DdlPhase.POST_DATA)
        pre shouldNotContain "FOREIGN KEY"
        post shouldContain "ALTER TABLE [orders] ADD CONSTRAINT [fk_orders_customer_id] FOREIGN KEY ([customer_id]) " +
            "REFERENCES [customers] ([id]);"
    }

    test("partitioning is not rendered: plain table plus E055") {
        val table = idTable("d" to col(NeutralType.Date, required = true)).copy(
            partitioning = PartitionConfig(PartitionType.RANGE, key = listOf("d")),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldNotContain "PARTITION"
        result.notesWithCode("E055").single().objectName shouldBe "t"
        result.codes() shouldNotContain "E065"
    }

    test("sequence-backed default renders NEXT VALUE FOR") {
        val table = idTable("num" to col(NeutralType.BigInteger, default = DefaultValue.SequenceNextVal("inv_seq")))
        val ddl = generator.generate(
            schema(tables = mapOf("t" to table), sequences = mapOf("inv_seq" to dev.dmigrate.core.model.SequenceDefinition())),
        ).render()
        ddl shouldContain "[num] BIGINT CONSTRAINT [df_t_num] DEFAULT NEXT VALUE FOR [inv_seq]"
    }

    test("a table without notes renders clean (no skipped objects)") {
        val result = generator.generate(schema(tables = mapOf("users" to idTable("n" to col(NeutralType.Text(10))))))
        result.skippedObjects.shouldBeEmpty()
        result.notes.shouldBeEmpty()
        result.codes() shouldNotContain "W138"
    }

    test("UNIQUE and PRIMARY KEY on LOB columns are not rendered (E057); bounded columns stay key-eligible") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "code" to col(NeutralType.Text(), required = true),
                "email" to col(NeutralType.Text(), unique = true),
                "doc" to col(NeutralType.Json),
                "short" to col(NeutralType.Text(50), unique = true, required = true),
            ),
            primaryKey = listOf("code"),
            constraints = listOf(ConstraintDefinition("uq_doc", ConstraintType.UNIQUE, columns = listOf("doc"))),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        val sql = result.statements.joinToString("\n") { it.sql }
        sql shouldNotContain "PRIMARY KEY"
        sql shouldNotContain "[uq_t_email]"
        sql shouldNotContain "[uq_doc]"
        sql shouldContain "[short] NVARCHAR(50) NOT NULL CONSTRAINT [uq_t_short] UNIQUE"
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("uq_t_email", "uq_doc", "pk_t")
    }

    test("UNIQUE on a primary-key column without explicit required does not emit W138") {
        val table = TableDefinition(
            columns = linkedMapOf("code" to col(NeutralType.Text(20), unique = true)),
            primaryKey = listOf("code"),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldContain "[code] NVARCHAR(20) CONSTRAINT [uq_t_code] UNIQUE"
        result.notesWithCode("W138").shouldBeEmpty()
    }

    test("identity generation on a non-identity-capable type is dropped with W140") {
        val table = TableDefinition(
            columns = linkedMapOf(
                "id" to col(NeutralType.Identifier(autoIncrement = true)),
                "f" to col(NeutralType.Float(), generation = ColumnGeneration.Identity(IdentityMode.ALWAYS)),
            ),
            primaryKey = listOf("id"),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table)))
        result.render() shouldContain "[f] FLOAT,"
        result.notesWithCode("W140").single().objectName shouldBe "t.f"
    }

    test("domain base types resolve PostgreSQL catalog names; unresolvable base types are E053, VALUE inside literals is kept") {
        val customTypes = mapOf(
            "ts_dom" to CustomTypeDefinition(CustomTypeKind.DOMAIN, baseType = "timestamp"),
            "tstz_dom" to CustomTypeDefinition(CustomTypeKind.DOMAIN, baseType = "timestamptz"),
            "big_dom" to CustomTypeDefinition(CustomTypeKind.DOMAIN, baseType = "int8"),
            "odd_dom" to CustomTypeDefinition(CustomTypeKind.DOMAIN, baseType = "inet"),
            "lit_dom" to CustomTypeDefinition(
                CustomTypeKind.DOMAIN, baseType = "text", precision = 30,
                check = "VALUE <> 'NO VALUE' AND value IN ('value', 'other')",
            ),
        )
        val table = idTable(
            "ts" to col(NeutralType.Enum(refType = "ts_dom")),
            "tstz" to col(NeutralType.Enum(refType = "tstz_dom")),
            "big" to col(NeutralType.Enum(refType = "big_dom")),
            "odd" to col(NeutralType.Enum(refType = "odd_dom")),
            "lit" to col(NeutralType.Enum(refType = "lit_dom")),
        )
        val result = generator.generate(schema(tables = mapOf("t" to table), customTypes = customTypes))
        val ddl = result.render()
        ddl shouldContain "[ts] DATETIME2"
        ddl shouldContain "[tstz] DATETIMEOFFSET"
        ddl shouldContain "[big] BIGINT"
        ddl shouldContain "[odd] NVARCHAR(MAX)"
        ddl shouldContain "[lit] NVARCHAR(30) CONSTRAINT [ck_t_lit] CHECK ([lit] <> 'NO VALUE' AND [lit] IN ('value', 'other'))"
        result.notesWithCode("E053").single().objectName shouldBe "t.odd"
    }

    test("self-referencing and cyclic cascades are rendered as NO ACTION with E057; independent cascades stay") {
        val employees = idTable(
            "manager_id" to col(
                NeutralType.Integer,
                references = ReferenceDefinition("employees", "id", onDelete = ReferentialAction.SET_NULL),
            ),
        )
        val orders = idTable(
            "customer_id" to col(
                NeutralType.Integer, required = true,
                references = ReferenceDefinition("customers", "id", onDelete = ReferentialAction.CASCADE),
            ),
        )
        val result = generator.generate(
            schema(tables = mapOf("customers" to idTable(), "orders" to orders, "employees" to employees)),
        )
        val ddl = result.render()
        ddl shouldContain "CONSTRAINT [fk_employees_manager_id] FOREIGN KEY ([manager_id]) " +
            "REFERENCES [employees] ([id]) ON DELETE NO ACTION"
        ddl shouldContain "CONSTRAINT [fk_orders_customer_id] FOREIGN KEY ([customer_id]) " +
            "REFERENCES [customers] ([id]) ON DELETE CASCADE"
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("fk_employees_manager_id")
    }

    test("diamond cascade paths neutralise the second path (SQL Server error 1785)") {
        val a = idTable()
        fun cascadeRef(table: String) = ReferenceDefinition(table, "id", onDelete = ReferentialAction.CASCADE)
        val b = idTable("a_id" to col(NeutralType.Integer, references = cascadeRef("a")))
        val c = idTable("a_id" to col(NeutralType.Integer, references = cascadeRef("a")))
        val d = idTable(
            "b_id" to col(NeutralType.Integer, references = cascadeRef("b")),
            "c_id" to col(NeutralType.Integer, references = cascadeRef("c")),
        )
        val result = generator.generate(schema(tables = linkedMapOf("a" to a, "b" to b, "c" to c, "d" to d)))
        val ddl = result.render()
        ddl shouldContain "CONSTRAINT [fk_d_b_id] FOREIGN KEY ([b_id]) REFERENCES [b] ([id]) ON DELETE CASCADE"
        ddl shouldContain "CONSTRAINT [fk_d_c_id] FOREIGN KEY ([c_id]) REFERENCES [c] ([id]) ON DELETE NO ACTION"
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("fk_d_c_id")
    }

    test("a cascading cycle rendered via ALTER TABLE is neutralised on the closing edge") {
        val a = idTable(
            "b_id" to col(NeutralType.Integer, references = ReferenceDefinition("b", "id", onDelete = ReferentialAction.CASCADE)),
        )
        val b = idTable(
            "a_id" to col(NeutralType.Integer, references = ReferenceDefinition("a", "id", onUpdate = ReferentialAction.CASCADE)),
        )
        val result = generator.generate(schema(tables = linkedMapOf("a" to a, "b" to b)))
        val alters = result.statements.filter { it.sql.startsWith("ALTER TABLE") }
        alters.map { it.sql } shouldBe listOf(
            "ALTER TABLE [a] ADD CONSTRAINT [fk_a_b_id] FOREIGN KEY ([b_id]) REFERENCES [b] ([id]) ON DELETE CASCADE;",
            "ALTER TABLE [b] ADD CONSTRAINT [fk_b_a_id] FOREIGN KEY ([a_id]) REFERENCES [a] ([id]) ON UPDATE NO ACTION;",
        )
        result.notesWithCode("E057").map { it.objectName } shouldBe listOf("fk_b_a_id")
    }

    test("domain over float keeps double precision; timezone-aware current_timestamp default uses SYSDATETIMEOFFSET") {
        val customTypes = mapOf("ratio" to CustomTypeDefinition(CustomTypeKind.DOMAIN, baseType = "float"))
        val table = idTable(
            "r" to col(NeutralType.Enum(refType = "ratio")),
            "at" to col(NeutralType.DateTime(timezone = true), default = DefaultValue.FunctionCall("current_timestamp")),
            "plain" to col(NeutralType.DateTime(), default = DefaultValue.FunctionCall("current_timestamp")),
        )
        val ddl = generator.generate(schema(tables = mapOf("t" to table), customTypes = customTypes)).render()
        ddl shouldContain "[r] FLOAT"
        ddl shouldContain "[at] DATETIMEOFFSET CONSTRAINT [df_t_at] DEFAULT SYSDATETIMEOFFSET()"
        ddl shouldContain "[plain] DATETIME2 CONSTRAINT [df_t_plain] DEFAULT CURRENT_TIMESTAMP"
    }
})
