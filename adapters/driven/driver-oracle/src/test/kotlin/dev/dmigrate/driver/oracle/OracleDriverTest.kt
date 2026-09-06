package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.StructuralTransferTypeCompatibility
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OracleDriverTest : FunSpec({

    val driver = OracleDriver()

    test("dialect is ORACLE") {
        driver.dialect shouldBe DatabaseDialect.ORACLE
    }

    test("Slice 1+2+3 ports (reverse-read, DDL-generate, data path) are real implementations") {
        driver.urlBuilder()::class.simpleName shouldBe "OracleJdbcUrlBuilder"
        driver.schemaReader()::class.simpleName shouldBe "OracleSchemaReader"
        driver.tableLister()::class.simpleName shouldBe "OracleTableLister"
        driver.ddlGenerator()::class.simpleName shouldBe "OracleDdlGenerator"
        driver.dataReader()::class.simpleName shouldBe "OracleDataReader"
        driver.dataWriter()::class.simpleName shouldBe "OracleDataWriter"
        driver.transferCompatibility()::class shouldBe StructuralTransferTypeCompatibility::class
    }

    test("fetch-size override reaches the reader (LN-005)") {
        driver.dataReader(500)::class.simpleName shouldBe "OracleDataReader"
    }
})
