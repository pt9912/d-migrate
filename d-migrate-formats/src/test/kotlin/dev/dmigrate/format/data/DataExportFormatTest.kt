package dev.dmigrate.format.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DataExportFormatTest : FunSpec({

    test("entries cover JSON, YAML, CSV") {
        DataExportFormat.entries.toSet() shouldBe setOf(
            DataExportFormat.JSON,
            DataExportFormat.YAML,
            DataExportFormat.CSV,
        )
    }

    test("cliName matches the canonical lowercase form") {
        DataExportFormat.JSON.cliName shouldBe "json"
        DataExportFormat.YAML.cliName shouldBe "yaml"
        DataExportFormat.CSV.cliName shouldBe "csv"
    }

    test("fromCli accepts the canonical names") {
        DataExportFormat.fromCli("json") shouldBe DataExportFormat.JSON
        DataExportFormat.fromCli("yaml") shouldBe DataExportFormat.YAML
        DataExportFormat.fromCli("csv") shouldBe DataExportFormat.CSV
    }

    test("fromCli is case-insensitive") {
        DataExportFormat.fromCli("JSON") shouldBe DataExportFormat.JSON
        DataExportFormat.fromCli("Yaml") shouldBe DataExportFormat.YAML
        DataExportFormat.fromCli("CSV") shouldBe DataExportFormat.CSV
    }

    test("fromCli rejects unknown formats with helpful message") {
        val ex = shouldThrow<IllegalArgumentException> {
            DataExportFormat.fromCli("xml")
        }
        ex.message!! shouldContain "xml"
        ex.message!! shouldContain "json"
    }

    test("ExportOptions defaults") {
        val options = ExportOptions()
        options.csvHeader shouldBe true
        options.csvDelimiter shouldBe ','
        options.csvQuote shouldBe '"'
        options.csvBom shouldBe false
        options.csvNullString shouldBe ""
    }
})
