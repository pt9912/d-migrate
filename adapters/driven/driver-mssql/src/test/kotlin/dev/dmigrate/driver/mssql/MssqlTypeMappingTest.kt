package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.FloatPrecision
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class MssqlTypeMappingTest : FunSpec({

    fun input(
        type: String,
        maxLength: Int? = null,
        precision: Int? = null,
        scale: Int? = null,
        identity: Boolean = false,
    ) = MssqlTypeMapping.ColumnInput(type, maxLength, precision, scale, identity)

    fun map(type: String, maxLength: Int? = null, precision: Int? = null, scale: Int? = null) =
        MssqlTypeMapping.mapColumn("t.c", input(type, maxLength, precision, scale)).type

    test("integer family") {
        map("int") shouldBe NeutralType.Integer
        map("bigint") shouldBe NeutralType.BigInteger
        map("smallint") shouldBe NeutralType.SmallInt
        map("tinyint") shouldBe NeutralType.SmallInt
    }

    test("bit folds to BooleanType") {
        map("bit") shouldBe NeutralType.BooleanType
    }

    test("decimal family carries precision/scale; money maps to fixed decimals") {
        map("decimal", precision = 10, scale = 2) shouldBe NeutralType.Decimal(10, 2)
        map("numeric", precision = 5, scale = 0) shouldBe NeutralType.Decimal(5, 0)
        map("money") shouldBe NeutralType.Decimal(19, 4)
        map("smallmoney") shouldBe NeutralType.Decimal(10, 4)
    }

    test("float family") {
        map("float") shouldBe NeutralType.Float(FloatPrecision.DOUBLE)
        map("real") shouldBe NeutralType.Float(FloatPrecision.SINGLE)
    }

    test("nvarchar length is halved from byte max_length; MAX becomes unbounded") {
        map("nvarchar", maxLength = 200) shouldBe NeutralType.Text(100)
        map("nvarchar", maxLength = -1) shouldBe NeutralType.Text(null)
        map("varchar", maxLength = 200) shouldBe NeutralType.Text(200)
        map("nchar", maxLength = 20) shouldBe NeutralType.Char(10)
        map("char", maxLength = 20) shouldBe NeutralType.Char(20)
    }

    test("temporal family") {
        map("date") shouldBe NeutralType.Date
        map("time") shouldBe NeutralType.Time
        map("datetime") shouldBe NeutralType.DateTime(timezone = false)
        map("datetime2") shouldBe NeutralType.DateTime(timezone = false)
        map("smalldatetime") shouldBe NeutralType.DateTime(timezone = false)
        map("datetimeoffset") shouldBe NeutralType.DateTime(timezone = true)
    }

    test("uniqueidentifier, xml, binary family") {
        map("uniqueidentifier") shouldBe NeutralType.Uuid
        map("xml") shouldBe NeutralType.Xml
        map("varbinary") shouldBe NeutralType.Binary
        map("binary") shouldBe NeutralType.Binary
        map("image") shouldBe NeutralType.Binary
    }

    test("unknown type maps to text with R301 warning") {
        val result = MssqlTypeMapping.mapColumn("t.h", input("hierarchyid"))
        result.type shouldBe NeutralType.Text(null)
        result.note.shouldNotBeNull()
        result.note!!.code shouldBe "R301"
        result.note!!.severity shouldBe SchemaReadSeverity.WARNING
    }

    test("known types carry no note") {
        MssqlTypeMapping.mapColumn("t.c", input("int")).note.shouldBeNull()
    }

    test("int identity is the 32-bit identifier contract") {
        val result = MssqlTypeMapping.mapColumn("t.id", input("int", identity = true))
        result.type shouldBe NeutralType.Identifier(autoIncrement = true)
        result.generation.shouldBeNull()
    }

    test("bigint identity keeps BigInteger and carries ALWAYS identity generation") {
        val result = MssqlTypeMapping.mapColumn("t.id", input("bigint", identity = true))
        result.type shouldBe NeutralType.BigInteger
        result.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
    }

    test("smallint identity keeps SmallInt with identity generation") {
        val result = MssqlTypeMapping.mapColumn("t.id", input("smallint", identity = true))
        result.type shouldBe NeutralType.SmallInt
        result.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
    }

    test("decimal identity keeps precision/scale with identity generation") {
        val result = MssqlTypeMapping.mapColumn("t.id", input("decimal", precision = 12, scale = 0, identity = true))
        result.type shouldBe NeutralType.Decimal(12, 0)
        result.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
    }

    // ── unwrapOuterParens ───────────────────────────────────────────

    test("unwrapOuterParens strips nested outer pairs only") {
        MssqlTypeMapping.unwrapOuterParens("((0))") shouldBe "0"
        MssqlTypeMapping.unwrapOuterParens("(getdate())") shouldBe "getdate()"
        MssqlTypeMapping.unwrapOuterParens("([a]>(0) AND [b]<(9))") shouldBe "[a]>(0) AND [b]<(9)"
        MssqlTypeMapping.unwrapOuterParens("(a) AND (b)") shouldBe "(a) AND (b)"
    }

    // ── parseDefault ────────────────────────────────────────────────

    test("parseDefault: string literals incl. N-prefix and quote unescaping") {
        MssqlTypeMapping.parseDefault("('x')", NeutralType.Text(null)) shouldBe
            DefaultValue.StringLiteral("x")
        MssqlTypeMapping.parseDefault("(N'O''Brien')", NeutralType.Text(null)) shouldBe
            DefaultValue.StringLiteral("O'Brien")
    }

    test("parseDefault: numbers") {
        MssqlTypeMapping.parseDefault("((42))", NeutralType.Integer) shouldBe
            DefaultValue.NumberLiteral(42L)
        MssqlTypeMapping.parseDefault("((1.5))", NeutralType.Decimal(3, 1)) shouldBe
            DefaultValue.NumberLiteral(1.5)
    }

    test("parseDefault: bit defaults become boolean literals") {
        MssqlTypeMapping.parseDefault("((1))", NeutralType.BooleanType) shouldBe
            DefaultValue.BooleanLiteral(true)
        MssqlTypeMapping.parseDefault("((0))", NeutralType.BooleanType) shouldBe
            DefaultValue.BooleanLiteral(false)
    }

    test("parseDefault: getdate()/sysdatetime() canonicalise to lowercase current_timestamp (MySQL/PG parity)") {
        MssqlTypeMapping.parseDefault("(getdate())", NeutralType.DateTime()) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
        MssqlTypeMapping.parseDefault("(sysdatetime())", NeutralType.DateTime()) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
    }

    test("parseDefault: string literals containing parentheses still unwrap and classify") {
        MssqlTypeMapping.parseDefault("('(')", NeutralType.Text(null)) shouldBe
            DefaultValue.StringLiteral("(")
        MssqlTypeMapping.parseDefault("(')')", NeutralType.Text(null)) shouldBe
            DefaultValue.StringLiteral(")")
    }

    test("parseDefault: bracketed sequence names keep embedded dots and escaped brackets") {
        MssqlTypeMapping.parseDefault("(NEXT VALUE FOR [dbo].[my.seq])", NeutralType.BigInteger) shouldBe
            DefaultValue.SequenceNextVal("my.seq")
        MssqlTypeMapping.parseDefault("(NEXT VALUE FOR [a]]b])", NeutralType.BigInteger) shouldBe
            DefaultValue.SequenceNextVal("a]b")
        MssqlTypeMapping.parseDefault("(NEXT VALUE FOR dbo.plain_seq)", NeutralType.BigInteger) shouldBe
            DefaultValue.SequenceNextVal("plain_seq")
    }

    test("parseDefault: other functions stay verbatim") {
        // `newid()` gehoert seit Slice 4 zu den vier neutralen Funktions-
        // Defaults (-> gen_uuid); verbatim bleibt, was T-SQL-eigen ist.
        MssqlTypeMapping.parseDefault("(host_name())", NeutralType.Text()) shouldBe
            DefaultValue.FunctionCall("host_name()")
    }

    test("parseDefault: NEXT VALUE FOR maps to SequenceNextVal") {
        MssqlTypeMapping.parseDefault("(NEXT VALUE FOR [dbo].[order_seq])", NeutralType.BigInteger) shouldBe
            DefaultValue.SequenceNextVal("order_seq")
    }

    test("parseDefault: null and empty yield null") {
        MssqlTypeMapping.parseDefault(null, NeutralType.Integer).shouldBeNull()
        MssqlTypeMapping.parseDefault("()", NeutralType.Integer).shouldBeNull()
    }

    test("geometry folds to the generic neutral geometry; geography carries the default SRID 4326 with R345") {
        MssqlTypeMapping.mapColumn("t.g", input("geometry")).let {
            it.type shouldBe NeutralType.Geometry()
            it.note.shouldBeNull()
        }
        MssqlTypeMapping.mapColumn("t.g", input("geography")).let {
            it.type shouldBe NeutralType.Geometry(srid = 4326)
            it.note.shouldNotBeNull()
            it.note!!.code shouldBe "R345"
            it.note!!.severity shouldBe SchemaReadSeverity.INFO
        }
    }

    test("sysdatetimeoffset() default canonicalises to current_timestamp like getdate()") {
        MssqlTypeMapping.parseDefault("(sysdatetimeoffset())", NeutralType.DateTime(timezone = true)) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
        MssqlTypeMapping.parseDefault("(getdate())", NeutralType.DateTime()) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
    }

    test("a reversed CHECK expression drops the T-SQL Unicode literal prefix") {
        // Live am Pagila-Leg gefunden: mit dem `N`-Praefix liest der Validator
        // das N als Spaltenbezug und lehnt das reverse-gelesene Schema mit
        // E012 ab (Slice 4).
        MssqlTypeMapping.normalizeCheckExpression("([rating]=N'NC-17' OR [rating]=N'PG-13')") shouldBe
            "rating='NC-17' OR rating='PG-13'"
        // Kleinschreibung ist derselbe Praefix.
        MssqlTypeMapping.normalizeCheckExpression("([c]=n'x')") shouldBe "c='x'"
        // Ein N INNERHALB eines Literals bleibt stehen ...
        MssqlTypeMapping.normalizeCheckExpression("([c]=N'ABN')") shouldBe "c='ABN'"
        MssqlTypeMapping.normalizeCheckExpression("([c]='N')") shouldBe "c='N'"
        // ... ebenso ein N als Namensbestandteil.
        MssqlTypeMapping.normalizeCheckExpression("([col_N]='x')") shouldBe "col_N='x'"
        // Verdoppelte Quotes sind das Escape, kein Literal-Ende.
        MssqlTypeMapping.normalizeCheckExpression("([c]=N'it''s N''ok''')") shouldBe "c='it''s N''ok'''"
        // Ausdruecke ohne Literale: nur Paren-Unwrap und Quoting.
        MssqlTypeMapping.normalizeCheckExpression("([score]>=(0))") shouldBe "score>=(0)"
    }

    test("a reversed CHECK expression drops T-SQL bracket quoting") {
        // Klammer-Quoting wird neutral: unquotiert, wo eindeutig.
        MssqlTypeMapping.normalizeCheckExpression("([rating]=N'NC-17')") shouldBe "rating='NC-17'"
        MssqlTypeMapping.normalizeCheckExpression("([dbo].[t].[c]>(0))") shouldBe "dbo.t.c>(0)"
        // Quotierungsbeduerftige Namen bekommen ANSI-Doppelquotes; `]]` ist das
        // T-SQL-Escape fuer eine schliessende Klammer.
        MssqlTypeMapping.normalizeCheckExpression("([my col]<>'')") shouldBe "\"my col\"<>''"
        MssqlTypeMapping.normalizeCheckExpression("([od]]d]<>'')") shouldBe "\"od]d\"<>''"
        // Eine Klammer INNERHALB eines Literals bleibt stehen.
        MssqlTypeMapping.normalizeCheckExpression("([c]='[x]')") shouldBe "c='[x]'"
    }

    test("the T-SQL catalog spellings of the four neutral function defaults are canonicalised") {
        // SQL Server speichert nicht die geschriebene Form: aus dem von
        // d-migrate erzeugten CAST(GETDATE() AS DATE) wird im Katalog
        // CONVERT([date],getdate()). Ohne Erkennung landet der Default als
        // String-Literal im Zielskript (live am Pagila-Leg gefunden, Slice 4).
        MssqlTypeMapping.parseDefault("(CONVERT([date],getdate()))", NeutralType.Date) shouldBe
            DefaultValue.FunctionCall("current_date")
        MssqlTypeMapping.parseDefault("(CONVERT([date], getdate()))", NeutralType.Date) shouldBe
            DefaultValue.FunctionCall("current_date")
        MssqlTypeMapping.parseDefault("(CAST(GETDATE() AS DATE))", NeutralType.Date) shouldBe
            DefaultValue.FunctionCall("current_date")
        MssqlTypeMapping.parseDefault("(CONVERT([time],getdate()))", NeutralType.Time) shouldBe
            DefaultValue.FunctionCall("current_time")
        MssqlTypeMapping.parseDefault("(newid())", NeutralType.Uuid) shouldBe
            DefaultValue.FunctionCall("gen_uuid")
        // Ein fremder Funktions-Default bleibt unveraendert stehen.
        MssqlTypeMapping.parseDefault("(dbo.next_code())", NeutralType.Text()) shouldBe
            DefaultValue.FunctionCall("dbo.next_code()")
    }
})
