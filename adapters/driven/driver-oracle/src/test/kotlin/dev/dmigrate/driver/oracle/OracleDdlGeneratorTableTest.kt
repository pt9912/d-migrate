package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintReferenceDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.PartitionConfig
import dev.dmigrate.core.model.PartitionType
import dev.dmigrate.core.model.ReferenceDefinition
import dev.dmigrate.core.model.ReferentialAction
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class OracleDdlGeneratorTableTest : FunSpec({

    val generator = OracleDdlGenerator()

    fun schema(tables: Map<String, TableDefinition>, customTypes: Map<String, CustomTypeDefinition> = emptyMap()) =
        SchemaDefinition(name = "s", version = "1.0", tables = tables, customTypes = customTypes)

    fun tableSql(schemaDef: SchemaDefinition): String =
        generator.generate(schemaDef).render()

    /** Nur das reine `CREATE TABLE`-SQL, ohne die vorangestellten Notiz-Kommentare. */
    fun createTableSql(result: dev.dmigrate.driver.DdlResult): String =
        result.statements.single { it.sql.startsWith("CREATE TABLE") }.sql

    // Oracle ist an dieser Stelle strenger als die vier anderen Dialekte:
    // die DEFAULT-Klausel MUSS vor der Inline-Constraint stehen.
    // `NOT NULL DEFAULT x` scheitert mit ORA-03076. Kein Golden deckte die
    // Kombination ab (dort ist keine DEFAULT-Spalte zugleich NOT NULL) --
    // aufgefallen erst, als der Sample-DB-Harness Pagila anwendete.
    test("a required column with a default renders DEFAULT before NOT NULL (ORA-03076)") {
        val table = TableDefinition(
            columns = mapOf(
                "last_update" to ColumnDefinition(
                    type = NeutralType.DateTime(timezone = true),
                    required = true,
                    default = dev.dmigrate.core.model.DefaultValue.FunctionCall("current_timestamp"),
                    ordinal = 1,
                ),
            ),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "\"last_update\" TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL"
        sql shouldNotContain "NOT NULL DEFAULT"
    }

    // Oracle kennt kein `CACHE 1` (ORA-04010, Minimum ist 2) -- PostgreSQLs
    // Sequenz-Default ist aber genau 1, sodass jede reverse-gelesene
    // PG-Sequenz sonst unanwendbare DDL ergibt.
    test("a sequence with cache 1 renders NOCACHE, not CACHE 1 (ORA-04010)") {
        val schemaDef = SchemaDefinition(
            name = "s", version = "1.0",
            sequences = mapOf(
                "s_one" to dev.dmigrate.core.model.SequenceDefinition(start = 1, increment = 1, cache = 1),
                "s_two" to dev.dmigrate.core.model.SequenceDefinition(start = 1, increment = 1, cache = 2),
            ),
        )
        val sql = generator.generate(schemaDef).render()
        sql shouldContain "\"s_one\""
        sql shouldNotContain "CACHE 1 "
        sql shouldNotContain "CACHE 1;"
        // Der zulaessige Wert bleibt unangetastet.
        sql shouldContain "CACHE 2"
    }

    // Oracle laesst TIMESTAMP WITH TIME ZONE nicht als Schluesselspalte zu
    // (ORA-02329) -- dieselbe Fehlernummer wie bei CLOB/BLOB, aber die
    // weniger bekannte Haelfte: PG, MySQL und SQL Server erlauben es.
    test("a primary key over a TIMESTAMP WITH TIME ZONE column is skipped with a note (ORA-02329)") {
        val table = TableDefinition(
            columns = mapOf(
                "payment_date" to ColumnDefinition(type = NeutralType.DateTime(timezone = true), ordinal = 1),
                "payment_id" to ColumnDefinition(type = NeutralType.Identifier(), ordinal = 2),
            ),
            primaryKey = listOf("payment_date", "payment_id"),
        )
        val result = generator.generate(schema(mapOf("payment" to table)))
        createTableSql(result) shouldNotContain "PRIMARY KEY"
        val note = result.notes.single { it.code == "E057" }
        note.message shouldContain "payment_date"
        note.message shouldContain "ORA-02329"
    }

    test("plain columns render NOT NULL, DEFAULT and inline named UNIQUE") {
        val table = TableDefinition(
            columns = mapOf(
                "name" to ColumnDefinition(type = NeutralType.Text(100), required = true, ordinal = 1),
                "email" to ColumnDefinition(type = NeutralType.Text(254), unique = true, ordinal = 2),
                "active" to ColumnDefinition(
                    type = NeutralType.BooleanType,
                    default = dev.dmigrate.core.model.DefaultValue.BooleanLiteral(true),
                    ordinal = 3,
                ),
            ),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "\"name\" VARCHAR2(100) NOT NULL"
        sql shouldContain "\"email\" VARCHAR2(254) CONSTRAINT \"uq_t_email\" UNIQUE"
        sql shouldContain "\"active\" NUMBER(1) DEFAULT 1"
        // Oracle kennt keine benannten DEFAULT-Constraints.
        sql shouldNotContain "CONSTRAINT \"df_"
    }

    test("Identifier autoIncrement folds to NUMBER(9) GENERATED ALWAYS AS IDENTITY") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true), ordinal = 1)),
            primaryKey = listOf("id"),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "\"id\" NUMBER(9) GENERATED ALWAYS AS IDENTITY"
    }

    test("ColumnGeneration.Identity BY_DEFAULT renders GENERATED BY DEFAULT AS IDENTITY on the reverse-read base type") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.BigInteger,
                    generation = ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = "iseq"),
                    ordinal = 1,
                ),
            ),
            primaryKey = listOf("id"),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "\"id\" NUMBER(18) GENERATED BY DEFAULT AS IDENTITY"
    }

    test("identity generation on a non-numeric type is dropped with a W151 note, column stays plain") {
        val table = TableDefinition(
            columns = mapOf(
                "code" to ColumnDefinition(
                    type = NeutralType.Text(10),
                    generation = ColumnGeneration.Identity(mode = IdentityMode.ALWAYS),
                    ordinal = 1,
                ),
            ),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.render() shouldContain "\"code\" VARCHAR2(10)"
        result.notes.single().code shouldBe "W151"
    }

    test("default on an identity column is dropped with W151") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.Identifier(autoIncrement = true),
                    default = dev.dmigrate.core.model.DefaultValue.NumberLiteral(1L),
                    ordinal = 1,
                ),
            ),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        createTableSql(result) shouldNotContain "DEFAULT"
        result.notes.single().code shouldBe "W151"
    }

    test("inline enum with values renders VARCHAR2(width) + named CHECK") {
        val table = TableDefinition(
            columns = mapOf(
                "status" to ColumnDefinition(type = NeutralType.Enum(values = listOf("open", "closed")), required = true, ordinal = 1),
            ),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "\"status\" VARCHAR2(6) NOT NULL"
        sql shouldContain "CONSTRAINT \"ck_t_status\" CHECK (\"status\" IN ('open', 'closed'))"
    }

    test("enum refType resolves values from a named ENUM custom type") {
        val table = TableDefinition(
            columns = mapOf(
                "status" to ColumnDefinition(type = NeutralType.Enum(refType = "status_enum"), ordinal = 1),
            ),
        )
        val customTypes = mapOf(
            "status_enum" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "bb")),
        )
        val sql = tableSql(schema(mapOf("t" to table), customTypes))
        sql shouldContain "VARCHAR2(2)"
        sql shouldContain "IN ('a', 'bb')"
    }

    test("enum refType pointing at a DOMAIN custom type renders CLOB with an E053 note") {
        val table = TableDefinition(
            columns = mapOf(
                "value" to ColumnDefinition(type = NeutralType.Enum(refType = "money_domain"), ordinal = 1),
            ),
        )
        val customTypes = mapOf(
            "money_domain" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "numeric"),
        )
        val result = generator.generate(schema(mapOf("t" to table), customTypes))
        result.render() shouldContain "\"value\" CLOB"
        result.notes.single().code shouldBe "E053"
    }

    test("composite custom type is rejected with E054") {
        val customTypes = mapOf(
            "point3d" to CustomTypeDefinition(kind = CustomTypeKind.COMPOSITE, fields = emptyMap()),
        )
        val result = generator.generate(schema(emptyMap(), customTypes))
        result.notes.single().code shouldBe "E054"
    }

    test("UNIQUE/PRIMARY KEY on a CLOB column is rejected with E057 instead of invalid DDL") {
        val table = TableDefinition(
            columns = mapOf("big" to ColumnDefinition(type = NeutralType.Text(null), unique = true, ordinal = 1)),
            primaryKey = listOf("big"),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        createTableSql(result) shouldNotContain "UNIQUE"
        createTableSql(result) shouldNotContain "PRIMARY KEY"
        result.notes.map { it.code } shouldBe listOf("E057", "E057")
    }

    test("EXCLUDE constraint is rejected with E054 (PostgreSQL-only feature)") {
        val table = TableDefinition(
            columns = mapOf("a" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            constraints = listOf(ConstraintDefinition(name = "ex_a", type = ConstraintType.EXCLUDE, expression = "a WITH =")),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.notes.single().code shouldBe "E054"
    }

    test("named CHECK/UNIQUE table constraints render inline") {
        val table = TableDefinition(
            columns = mapOf(
                "a" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "b" to ColumnDefinition(type = NeutralType.Integer, ordinal = 2),
            ),
            constraints = listOf(
                ConstraintDefinition(name = "ck_pos", type = ConstraintType.CHECK, expression = "a > 0"),
                ConstraintDefinition(name = "uq_ab", type = ConstraintType.UNIQUE, columns = listOf("a", "b")),
            ),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "CONSTRAINT \"ck_pos\" CHECK (a > 0)"
        sql shouldContain "CONSTRAINT \"uq_ab\" UNIQUE (\"a\", \"b\")"
    }

    test("foreign key renders CASCADE/SET NULL; RESTRICT/NO_ACTION are omitted; SET_DEFAULT is dropped with W153") {
        fun fkTable(action: ReferentialAction?) = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "parent_id" to ColumnDefinition(
                    type = NeutralType.Integer,
                    references = ReferenceDefinition(table = "parents", column = "id", onDelete = action),
                    ordinal = 2,
                ),
            ),
        )
        val cascade = tableSql(schema(mapOf("t" to fkTable(ReferentialAction.CASCADE))))
        cascade shouldContain "ON DELETE CASCADE"

        val setNull = tableSql(schema(mapOf("t" to fkTable(ReferentialAction.SET_NULL))))
        setNull shouldContain "ON DELETE SET NULL"

        val restrict = tableSql(schema(mapOf("t" to fkTable(ReferentialAction.RESTRICT))))
        restrict shouldNotContain "ON DELETE"

        val noAction = tableSql(schema(mapOf("t" to fkTable(ReferentialAction.NO_ACTION))))
        noAction shouldNotContain "ON DELETE"

        val setDefaultResult = generator.generate(schema(mapOf("t" to fkTable(ReferentialAction.SET_DEFAULT))))
        createTableSql(setDefaultResult) shouldNotContain "ON DELETE"
        setDefaultResult.notes.any { it.code == "W153" } shouldBe true
    }

    test("constraint-level foreign key renders via ConstraintReferenceDefinition") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1),
                "parent_id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 2),
            ),
            constraints = listOf(
                ConstraintDefinition(
                    name = "fk_parent", type = ConstraintType.FOREIGN_KEY, columns = listOf("parent_id"),
                    references = ConstraintReferenceDefinition(
                        table = "parents", columns = listOf("id"), onDelete = ReferentialAction.CASCADE,
                    ),
                ),
            ),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "CONSTRAINT \"fk_parent\" FOREIGN KEY (\"parent_id\") REFERENCES \"parents\" (\"id\") ON DELETE CASCADE"
    }

    test("primary key renders as a named constraint") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            primaryKey = listOf("id"),
        )
        val sql = tableSql(schema(mapOf("t" to table)))
        sql shouldContain "CONSTRAINT \"pk_t\" PRIMARY KEY (\"id\")"
    }

    test("partitioning is rejected with E055; table still renders as plain") {
        val table = TableDefinition(
            columns = mapOf("id" to ColumnDefinition(type = NeutralType.Integer, ordinal = 1)),
            partitioning = PartitionConfig(type = PartitionType.RANGE, key = listOf("id")),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.render() shouldContain "CREATE TABLE \"t\""
        result.notes.single().code shouldBe "E055"
    }

    test("NUMBER precision beyond 38 is clamped with a W148 note") {
        val table = TableDefinition(
            columns = mapOf("amount" to ColumnDefinition(type = NeutralType.Decimal(50, 10), ordinal = 1)),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.render() shouldContain "NUMBER(38,10)"
        result.notes.single().code shouldBe "W148"
    }

    test("Text/Char widened to CLOB is flagged with W145") {
        val table = TableDefinition(
            columns = mapOf("bio" to ColumnDefinition(type = NeutralType.Text(5000), ordinal = 1)),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.notes.single().code shouldBe "W145"
    }

    test("Time column is flagged with W146; Date column with an informational W147") {
        val table = TableDefinition(
            columns = mapOf(
                "t" to ColumnDefinition(type = NeutralType.Time, ordinal = 1),
                "d" to ColumnDefinition(type = NeutralType.Date, ordinal = 2),
            ),
        )
        val result = generator.generate(schema(mapOf("tbl" to table)))
        result.notes.map { it.code } shouldBe listOf("W146", "W147")
    }

    test("Array column is flagged with W149; FullText column reuses the shared W132 pool code") {
        val table = TableDefinition(
            columns = mapOf(
                "tags" to ColumnDefinition(type = NeutralType.Array("text"), ordinal = 1),
                "search" to ColumnDefinition(type = NeutralType.FullText, ordinal = 2),
            ),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.notes.map { it.code } shouldBe listOf("W149", "W132")
    }

    test("gen_uuid default is flagged with an informational W150 (dash-free hex format)") {
        val table = TableDefinition(
            columns = mapOf(
                "id" to ColumnDefinition(
                    type = NeutralType.Uuid,
                    default = dev.dmigrate.core.model.DefaultValue.FunctionCall("gen_uuid"),
                    ordinal = 1,
                ),
            ),
        )
        val result = generator.generate(schema(mapOf("t" to table)))
        result.render() shouldContain "DEFAULT RAWTOHEX(SYS_GUID())"
        result.notes.single().code shouldBe "W150"
    }
})
