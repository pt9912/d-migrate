package dev.dmigrate.driver.mssql

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class MssqlViewDefinitionScannerTest : FunSpec({

    test("plain CREATE VIEW ... AS SELECT") {
        MssqlViewDefinitionScanner.queryOf("CREATE VIEW [dbo].[v] AS SELECT 1 AS one") shouldBe
            "SELECT 1 AS one"
    }

    test("column list containing no AS keyword at top level") {
        MssqlViewDefinitionScanner.queryOf(
            "CREATE VIEW dbo.v (a, b) AS SELECT x, y FROM t",
        ) shouldBe "SELECT x, y FROM t"
    }

    test("WITH SCHEMABINDING between name and AS") {
        MssqlViewDefinitionScanner.queryOf(
            "CREATE VIEW dbo.v WITH SCHEMABINDING AS SELECT x FROM dbo.t",
        ) shouldBe "SELECT x FROM dbo.t"
    }

    test("CAST(... AS int) inside parentheses does not terminate the scan early") {
        MssqlViewDefinitionScanner.queryOf(
            "CREATE VIEW v (a) AS SELECT CAST(x AS int) FROM t",
        ) shouldBe "SELECT CAST(x AS int) FROM t"
    }

    test("AS inside comments and strings is ignored") {
        MssqlViewDefinitionScanner.queryOf(
            "CREATE /* AS */ VIEW v -- AS nothing\n AS SELECT 'AS' AS lit",
        ) shouldBe "SELECT 'AS' AS lit"
    }

    test("bracketed identifiers containing AS are ignored") {
        MssqlViewDefinitionScanner.queryOf(
            "CREATE VIEW [dbo].[v AS x] AS SELECT 1 AS c",
        ) shouldBe "SELECT 1 AS c"
    }

    test("definition without AS yields null") {
        MssqlViewDefinitionScanner.queryOf("CREATE VIEW broken").shouldBeNull()
    }

    test("case-insensitive keywords") {
        MssqlViewDefinitionScanner.queryOf("create view v as select 1") shouldBe "select 1"
    }
})
