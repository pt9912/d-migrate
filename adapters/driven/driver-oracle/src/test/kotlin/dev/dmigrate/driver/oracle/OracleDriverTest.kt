package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class OracleDriverTest : FunSpec({

    val driver = OracleDriver()

    test("dialect is ORACLE") {
        driver.dialect shouldBe DatabaseDialect.ORACLE
    }

    test("Slice 1 ports (reverse-read) are real implementations") {
        driver.urlBuilder()::class.simpleName shouldBe "OracleJdbcUrlBuilder"
        driver.schemaReader()::class.simpleName shouldBe "OracleSchemaReader"
        driver.tableLister()::class.simpleName shouldBe "OracleTableLister"
    }

    test("write-/generate-side ports are unreachable stubs, gated by DialectCommandGate") {
        shouldThrow<IllegalStateException> { driver.ddlGenerator() }
            .message shouldBe "unreachable: DialectCommandGate rejects oracle for schema generate (ADR 0052)"
        shouldThrow<IllegalStateException> { driver.dataReader() }
            .message shouldBe "unreachable: DialectCommandGate rejects oracle for data export/transfer (ADR 0052)"
        shouldThrow<IllegalStateException> { driver.dataWriter() }
            .message shouldBe "unreachable: DialectCommandGate rejects oracle for data import/transfer (ADR 0052)"
    }
})
