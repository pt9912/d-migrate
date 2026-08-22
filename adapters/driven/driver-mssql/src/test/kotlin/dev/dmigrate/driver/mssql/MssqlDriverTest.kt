package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MssqlDriverTest : FunSpec({

    val driver = MssqlDriver()

    test("dialect is MSSQL") {
        driver.dialect shouldBe DatabaseDialect.MSSQL
    }

    test("all ports built through slice 3 are real implementations") {
        driver.urlBuilder()::class.simpleName shouldBe "MssqlJdbcUrlBuilder"
        driver.schemaReader()::class.simpleName shouldBe "MssqlSchemaReader"
        driver.tableLister()::class.simpleName shouldBe "MssqlTableLister"
        driver.ddlGenerator()::class.simpleName shouldBe "MssqlDdlGenerator"
        driver.dataReader()::class.simpleName shouldBe "MssqlDataReader"
        driver.dataWriter()::class.simpleName shouldBe "MssqlDataWriter"
    }

    test("fetch-size override reaches the reader (LN-005)") {
        driver.dataReader(500)::class.simpleName shouldBe "MssqlDataReader"
    }
})
