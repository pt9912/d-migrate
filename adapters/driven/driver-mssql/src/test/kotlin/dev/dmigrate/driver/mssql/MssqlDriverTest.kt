package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MssqlDriverTest : FunSpec({

    val driver = MssqlDriver()

    test("dialect is MSSQL") {
        driver.dialect shouldBe DatabaseDialect.MSSQL
    }

    test("reverse-read ports are real implementations") {
        driver.urlBuilder()::class.simpleName shouldBe "MssqlJdbcUrlBuilder"
        driver.schemaReader()::class.simpleName shouldBe "MssqlSchemaReader"
        driver.tableLister()::class.simpleName shouldBe "MssqlTableLister"
    }

    test("gated ports assert the command-boundary invariant") {
        shouldThrow<IllegalStateException> { driver.ddlGenerator() }
            .message!! shouldContain "DialectCommandGate"
        shouldThrow<IllegalStateException> { driver.dataReader() }
            .message!! shouldContain "DialectCommandGate"
        shouldThrow<IllegalStateException> { driver.dataWriter() }
            .message!! shouldContain "DialectCommandGate"
    }
})
