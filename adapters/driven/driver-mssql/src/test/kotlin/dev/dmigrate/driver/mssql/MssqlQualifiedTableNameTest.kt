package dev.dmigrate.driver.mssql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MssqlQualifiedTableNameTest : FunSpec({

    fun parse(raw: String) = MssqlQualifiedTableName.parse(raw, defaultSchema = "dbo")

    test("bare name takes the default schema") {
        parse("orders") shouldBe MssqlQualifiedTableName("dbo", "orders")
    }

    test("schema-qualified name keeps both parts") {
        parse("sales.orders") shouldBe MssqlQualifiedTableName("sales", "orders")
    }

    test("bracketed parts survive, dots inside brackets do not split") {
        parse("[my schema].[my.table]") shouldBe MssqlQualifiedTableName("my schema", "my.table")
        parse("[weird]]name]") shouldBe MssqlQualifiedTableName("dbo", "weird]name")
    }

    test("three-part names keep the database, like the read path renders it") {
        parse("shopdb.sales.orders") shouldBe MssqlQualifiedTableName("sales", "orders", database = "shopdb")
        parse("shopdb.sales.orders").quotedPath() shouldBe "[shopdb].[sales].[orders]"
    }

    test("quotedPath brackets every part") {
        parse("sales.orders").quotedPath() shouldBe "[sales].[orders]"
        parse("orders").quotedPath() shouldBe "[dbo].[orders]"
        parse("[my schema].[my]]table]").quotedPath() shouldBe "[my schema].[my]]table]"
    }
})
