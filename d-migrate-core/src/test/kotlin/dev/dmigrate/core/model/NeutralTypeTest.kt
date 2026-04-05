package dev.dmigrate.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class NeutralTypeTest : FunSpec({

    test("Identifier with auto_increment") {
        val type = NeutralType.Identifier(autoIncrement = true)
        type.autoIncrement shouldBe true
    }

    test("Text with max_length") {
        val type = NeutralType.Text(maxLength = 255)
        type.maxLength shouldBe 255
    }

    test("Text without max_length") {
        val type = NeutralType.Text()
        type.maxLength shouldBe null
    }

    test("Char with length") {
        val type = NeutralType.Char(length = 2)
        type.length shouldBe 2
    }

    test("parameterless types are singletons") {
        NeutralType.Integer shouldBe NeutralType.Integer
        NeutralType.SmallInt shouldBe NeutralType.SmallInt
        NeutralType.BigInteger shouldBe NeutralType.BigInteger
        NeutralType.BooleanType shouldBe NeutralType.BooleanType
        NeutralType.Date shouldBe NeutralType.Date
        NeutralType.Time shouldBe NeutralType.Time
        NeutralType.Uuid shouldBe NeutralType.Uuid
        NeutralType.Json shouldBe NeutralType.Json
        NeutralType.Xml shouldBe NeutralType.Xml
        NeutralType.Binary shouldBe NeutralType.Binary
    }

    test("Float with precision") {
        NeutralType.Float(FloatPrecision.SINGLE).floatPrecision shouldBe FloatPrecision.SINGLE
        NeutralType.Float().floatPrecision shouldBe FloatPrecision.DOUBLE
    }

    test("Decimal with precision and scale") {
        val type = NeutralType.Decimal(precision = 10, scale = 2)
        type.precision shouldBe 10
        type.scale shouldBe 2
    }

    test("DateTime with timezone") {
        NeutralType.DateTime(timezone = true).timezone shouldBe true
        NeutralType.DateTime().timezone shouldBe false
    }

    test("Enum with values") {
        val type = NeutralType.Enum(values = listOf("a", "b", "c"))
        type.values shouldBe listOf("a", "b", "c")
        type.refType shouldBe null
    }

    test("Enum with ref_type") {
        val type = NeutralType.Enum(refType = "order_status")
        type.values shouldBe null
        type.refType shouldBe "order_status"
    }

    test("Array with element_type") {
        val type = NeutralType.Array(elementType = "text")
        type.elementType shouldBe "text"
    }

    test("Email is a singleton with fixed MAX_LENGTH") {
        NeutralType.Email shouldBe NeutralType.Email
        NeutralType.Email.MAX_LENGTH shouldBe 254
    }

    test("data class equality") {
        NeutralType.Text(maxLength = 100) shouldBe NeutralType.Text(maxLength = 100)
        NeutralType.Decimal(10, 2) shouldBe NeutralType.Decimal(10, 2)
    }

    test("when expression is exhaustive") {
        // Compile-time check: all branches covered
        val type: NeutralType = NeutralType.Integer
        val result = when (type) {
            is NeutralType.Identifier -> "identifier"
            is NeutralType.Text -> "text"
            is NeutralType.Char -> "char"
            is NeutralType.Integer -> "integer"
            is NeutralType.SmallInt -> "smallint"
            is NeutralType.BigInteger -> "biginteger"
            is NeutralType.Float -> "float"
            is NeutralType.Decimal -> "decimal"
            is NeutralType.BooleanType -> "boolean"
            is NeutralType.DateTime -> "datetime"
            is NeutralType.Date -> "date"
            is NeutralType.Time -> "time"
            is NeutralType.Uuid -> "uuid"
            is NeutralType.Json -> "json"
            is NeutralType.Xml -> "xml"
            is NeutralType.Binary -> "binary"
            NeutralType.Email -> "email"
            is NeutralType.Enum -> "enum"
            is NeutralType.Array -> "array"
        }
        result shouldBe "integer"
    }
})
