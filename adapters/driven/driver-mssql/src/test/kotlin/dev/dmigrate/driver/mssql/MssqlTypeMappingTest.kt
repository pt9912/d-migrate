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
        val result = MssqlTypeMapping.mapColumn("t.geo", input("geography"))
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
        MssqlTypeMapping.parseDefault("(newid())", NeutralType.Uuid) shouldBe
            DefaultValue.FunctionCall("newid()")
    }

    test("parseDefault: NEXT VALUE FOR maps to SequenceNextVal") {
        MssqlTypeMapping.parseDefault("(NEXT VALUE FOR [dbo].[order_seq])", NeutralType.BigInteger) shouldBe
            DefaultValue.SequenceNextVal("order_seq")
    }

    test("parseDefault: null and empty yield null") {
        MssqlTypeMapping.parseDefault(null, NeutralType.Integer).shouldBeNull()
        MssqlTypeMapping.parseDefault("()", NeutralType.Integer).shouldBeNull()
    }
})
