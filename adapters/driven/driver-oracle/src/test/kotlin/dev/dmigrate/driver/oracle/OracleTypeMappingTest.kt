package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadSeverity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class OracleTypeMappingTest : FunSpec({

    fun input(
        typeName: String,
        length: Int? = null,
        precision: Int? = null,
        scale: Int? = null,
        isIdentity: Boolean = false,
        identityGeneration: String? = null,
        identitySequenceName: String? = null,
    ) = OracleTypeMapping.ColumnInput(
        typeName = typeName, length = length, precision = precision, scale = scale,
        isIdentity = isIdentity, identityGeneration = identityGeneration,
        identitySequenceName = identitySequenceName,
    )

    test("NUMBER(1) folds to BooleanType (Oracle's 0/1 boolean convention)") {
        OracleTypeMapping.mapColumn("flag", input("NUMBER", precision = 1)).type shouldBe NeutralType.BooleanType
    }

    test("CHAR(1) is NOT folded to BooleanType (no reliable single-char signal)") {
        OracleTypeMapping.mapColumn("code", input("CHAR", length = 1)).type shouldBe NeutralType.Char(1)
    }

    test("NUMBER precision tiers into small/integer/biginteger/decimal") {
        OracleTypeMapping.mapColumn("c", input("NUMBER", precision = 4)).type shouldBe NeutralType.SmallInt
        OracleTypeMapping.mapColumn("c", input("NUMBER", precision = 9)).type shouldBe NeutralType.Integer
        OracleTypeMapping.mapColumn("c", input("NUMBER", precision = 18)).type shouldBe NeutralType.BigInteger
        OracleTypeMapping.mapColumn("c", input("NUMBER", precision = 20)).type shouldBe
            NeutralType.Decimal(20, 0)
    }

    test("NUMBER with positive scale is a Decimal regardless of precision") {
        OracleTypeMapping.mapColumn("c", input("NUMBER", precision = 10, scale = 2)).type shouldBe
            NeutralType.Decimal(10, 2)
    }

    test("unbound NUMBER (no precision/scale) falls back to Decimal(38, 10)") {
        OracleTypeMapping.mapColumn("c", input("NUMBER")).type shouldBe NeutralType.Decimal(38, 10)
    }

    test("FLOAT and BINARY_DOUBLE map to double precision; BINARY_FLOAT to single") {
        OracleTypeMapping.mapColumn("c", input("FLOAT")).type shouldBe
            NeutralType.Float(dev.dmigrate.core.model.FloatPrecision.DOUBLE)
        OracleTypeMapping.mapColumn("c", input("BINARY_DOUBLE")).type shouldBe
            NeutralType.Float(dev.dmigrate.core.model.FloatPrecision.DOUBLE)
        OracleTypeMapping.mapColumn("c", input("BINARY_FLOAT")).type shouldBe
            NeutralType.Float(dev.dmigrate.core.model.FloatPrecision.SINGLE)
    }

    test("VARCHAR2/NVARCHAR2 carry the character length") {
        OracleTypeMapping.mapColumn("c", input("VARCHAR2", length = 100)).type shouldBe
            NeutralType.Text(maxLength = 100)
        OracleTypeMapping.mapColumn("c", input("NVARCHAR2", length = 50)).type shouldBe
            NeutralType.Text(maxLength = 50)
    }

    test("CLOB/NCLOB/LONG map to unbounded text") {
        listOf("CLOB", "NCLOB", "LONG").forEach {
            OracleTypeMapping.mapColumn("c", input(it)).type shouldBe NeutralType.Text(maxLength = null)
        }
    }

    test("DATE maps to DateTime without timezone, carrying Oracle's time component") {
        OracleTypeMapping.mapColumn("c", input("DATE")).type shouldBe NeutralType.DateTime(timezone = false)
    }

    test("TIMESTAMP WITH TIME ZONE maps to DateTime with timezone; plain TIMESTAMP without") {
        OracleTypeMapping.mapColumn("c", input("TIMESTAMP(6) WITH TIME ZONE")).type shouldBe
            NeutralType.DateTime(timezone = true)
        OracleTypeMapping.mapColumn("c", input("TIMESTAMP(6)")).type shouldBe
            NeutralType.DateTime(timezone = false)
    }

    test("RAW/LONG RAW/BLOB map to binary") {
        listOf("RAW", "LONG RAW", "BLOB").forEach {
            OracleTypeMapping.mapColumn("c", input(it)).type shouldBe NeutralType.Binary
        }
    }

    test("an unknown type falls back to text and emits an R301 warning note") {
        val result = OracleTypeMapping.mapColumn("weird_col", input("SDO_GEOMETRY"))
        result.type shouldBe NeutralType.Text(maxLength = null)
        result.note?.code shouldBe "R301"
        result.note?.severity shouldBe SchemaReadSeverity.WARNING
        result.note?.objectName shouldBe "weird_col"
    }

    test("a known type emits no note") {
        OracleTypeMapping.mapColumn("c", input("VARCHAR2", length = 10)).note.shouldBeNull()
    }

    test("identity columns fold to ColumnGeneration.Identity, ignoring the base-type note") {
        val result = OracleTypeMapping.mapColumn(
            "id",
            input(
                "NUMBER", precision = 10, isIdentity = true,
                identityGeneration = "ALWAYS", identitySequenceName = "ISEQ\$\$_1",
            ),
        )
        result.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = "ISEQ\$\$_1")
        result.type shouldBe NeutralType.BigInteger
        result.note.shouldBeNull()
    }

    test("identity GENERATED BY DEFAULT maps to BY_DEFAULT mode") {
        val result = OracleTypeMapping.mapColumn(
            "id",
            input("NUMBER", precision = 10, isIdentity = true, identityGeneration = "BY DEFAULT"),
        )
        result.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.BY_DEFAULT, sequenceName = null)
    }

    test("identity precision tiers mirror the base numeric tiering") {
        OracleTypeMapping.mapColumn("id", input("NUMBER", precision = 9, isIdentity = true, identityGeneration = "ALWAYS"))
            .type shouldBe NeutralType.Integer
        OracleTypeMapping.mapColumn("id", input("NUMBER", precision = 18, isIdentity = true, identityGeneration = "ALWAYS"))
            .type shouldBe NeutralType.BigInteger
        OracleTypeMapping.mapColumn("id", input("NUMBER", isIdentity = true, identityGeneration = "ALWAYS"))
            .type shouldBe NeutralType.BigInteger
    }

    test("parseDefault handles null and empty/trailing-comma raw text") {
        OracleTypeMapping.parseDefault(null, NeutralType.Integer).shouldBeNull()
        OracleTypeMapping.parseDefault("   ", NeutralType.Integer).shouldBeNull()
    }

    test("parseDefault unescapes '' inside single-quoted string literals") {
        OracleTypeMapping.parseDefault("'it''s fine'", NeutralType.Text(null)) shouldBe
            DefaultValue.StringLiteral("it's fine")
    }

    test("parseDefault reads 0/1 as BooleanLiteral for a BooleanType column (NUMBER(1) convention)") {
        OracleTypeMapping.parseDefault("0", NeutralType.BooleanType) shouldBe DefaultValue.BooleanLiteral(false)
        OracleTypeMapping.parseDefault("1", NeutralType.BooleanType) shouldBe DefaultValue.BooleanLiteral(true)
    }

    test("parseDefault reads number literals") {
        OracleTypeMapping.parseDefault("42", NeutralType.Integer) shouldBe DefaultValue.NumberLiteral(42L)
        OracleTypeMapping.parseDefault("3.14", NeutralType.Decimal(10, 2)) shouldBe DefaultValue.NumberLiteral(3.14)
    }

    test("parseDefault reads <sequence>.NEXTVAL, with or without schema prefix") {
        OracleTypeMapping.parseDefault("MY_SEQ.NEXTVAL", NeutralType.Integer) shouldBe
            DefaultValue.SequenceNextVal("MY_SEQ")
        OracleTypeMapping.parseDefault("\"APP\".\"MY_SEQ\".NEXTVAL", NeutralType.Integer) shouldBe
            DefaultValue.SequenceNextVal("MY_SEQ")
    }

    test("parseDefault canonicalizes sysdate/systimestamp to the cross-dialect current_timestamp") {
        // Lowercase, wie bei MySQL/MSSQL -- toDefaultSql-Dispatchtabellen matchen
        // case-sensitiv auf diesen kanonischen Namen (siehe PostgresTypeMapper).
        OracleTypeMapping.parseDefault("sysdate", NeutralType.DateTime(false)) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
        OracleTypeMapping.parseDefault("systimestamp", NeutralType.DateTime(false)) shouldBe
            DefaultValue.FunctionCall("current_timestamp")
    }

    test("parseDefault recognizes gen_uuid/current_date/current_time from their Oracle generate-direction spellings") {
        // Gegenstuecke zu OracleTypeMapper.functionDefaultSql -- ohne diese
        // Erkennung wuerde ein schema-generate/schema-reverse-Rundgang die
        // neutrale Default-Bedeutung verlieren.
        OracleTypeMapping.parseDefault("RAWTOHEX(SYS_GUID())", NeutralType.Uuid) shouldBe
            DefaultValue.FunctionCall("gen_uuid")
        OracleTypeMapping.parseDefault("TRUNC(SYSDATE)", NeutralType.Date) shouldBe
            DefaultValue.FunctionCall("current_date")
        OracleTypeMapping.parseDefault("TO_CHAR(SYSDATE, 'HH24:MI:SS')", NeutralType.Time) shouldBe
            DefaultValue.FunctionCall("current_time")
        // Case-/Whitespace-tolerant, wie im Katalog gemeldet.
        OracleTypeMapping.parseDefault("trunc(sysdate)", NeutralType.Date) shouldBe
            DefaultValue.FunctionCall("current_date")
    }

    test("parseDefault falls back to a raw function call for anything else") {
        OracleTypeMapping.parseDefault("some_func()", NeutralType.Text(null)) shouldBe
            DefaultValue.FunctionCall("some_func()")
    }
})
