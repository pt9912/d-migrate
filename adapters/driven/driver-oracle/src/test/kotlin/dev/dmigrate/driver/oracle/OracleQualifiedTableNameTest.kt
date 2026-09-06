package dev.dmigrate.driver.oracle

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OracleQualifiedTableNameTest : FunSpec({

    fun parse(raw: String) = OracleQualifiedTableName.parse(raw, defaultSchema = "APP")

    test("bare name takes the default schema") {
        parse("orders") shouldBe OracleQualifiedTableName("APP", "orders")
    }

    test("schema-qualified name keeps both parts") {
        parse("sales.orders") shouldBe OracleQualifiedTableName("sales", "orders")
    }

    test("quotedPath double-quotes every part") {
        parse("sales.orders").quotedPath() shouldBe "\"sales\".\"orders\""
        parse("orders").quotedPath() shouldBe "\"APP\".\"orders\""
    }

    test("surrounding whitespace is trimmed") {
        parse("  sales . orders  ") shouldBe OracleQualifiedTableName("sales", "orders")
    }
})
