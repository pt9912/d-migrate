package dev.dmigrate.driver

import dev.dmigrate.format.data.DataExportFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

class PortsCommonTest : FunSpec({

    // ── DatabaseDialect enum ──────────────────────────────────────────

    test("DatabaseDialect has three values") {
        DatabaseDialect.entries.map { it.name } shouldBe listOf("POSTGRESQL", "MYSQL", "SQLITE")
    }

    test("DatabaseDialect valueOf round-trips") {
        DatabaseDialect.valueOf("POSTGRESQL") shouldBe DatabaseDialect.POSTGRESQL
        DatabaseDialect.valueOf("MYSQL") shouldBe DatabaseDialect.MYSQL
        DatabaseDialect.valueOf("SQLITE") shouldBe DatabaseDialect.SQLITE
    }

    test("DatabaseDialect.fromString recognises canonical names") {
        DatabaseDialect.fromString("postgresql") shouldBe DatabaseDialect.POSTGRESQL
        DatabaseDialect.fromString("mysql") shouldBe DatabaseDialect.MYSQL
        DatabaseDialect.fromString("sqlite") shouldBe DatabaseDialect.SQLITE
    }

    test("DatabaseDialect.fromString recognises aliases") {
        DatabaseDialect.fromString("postgres") shouldBe DatabaseDialect.POSTGRESQL
        DatabaseDialect.fromString("pg") shouldBe DatabaseDialect.POSTGRESQL
        DatabaseDialect.fromString("maria") shouldBe DatabaseDialect.MYSQL
        DatabaseDialect.fromString("mariadb") shouldBe DatabaseDialect.MYSQL
        DatabaseDialect.fromString("sqlite3") shouldBe DatabaseDialect.SQLITE
    }

    test("DatabaseDialect.fromString is case-insensitive") {
        DatabaseDialect.fromString("PostgreSQL") shouldBe DatabaseDialect.POSTGRESQL
        DatabaseDialect.fromString("MYSQL") shouldBe DatabaseDialect.MYSQL
        DatabaseDialect.fromString("SQLite3") shouldBe DatabaseDialect.SQLITE
    }

    test("DatabaseDialect.fromString throws on unknown dialect") {
        val ex = shouldThrow<IllegalArgumentException> {
            DatabaseDialect.fromString("oracle")
        }
        ex.message!! shouldContain "Unknown database dialect"
        ex.message!! shouldContain "oracle"
    }

    // ── DialectCapabilities ───────────────────────────────────────────

    test("DialectCapabilities.forDialect returns correct capabilities for PostgreSQL") {
        val caps = DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL)
        caps.supportsViews shouldBe true
        caps.supportsFunctions shouldBe true
        caps.supportsProcedures shouldBe true
        caps.supportsTriggers shouldBe true
        caps.supportsSequences shouldBe true
        caps.supportsCustomTypes shouldBe true
        caps.supportsPartitioning shouldBe true
        caps.supportsRoutineRewrite shouldBe false
    }

    test("DialectCapabilities.forDialect returns correct capabilities for MySQL") {
        val caps = DialectCapabilities.forDialect(DatabaseDialect.MYSQL)
        caps.supportsViews shouldBe true
        caps.supportsFunctions shouldBe true
        caps.supportsProcedures shouldBe true
        caps.supportsTriggers shouldBe true
        caps.supportsSequences shouldBe false
        caps.supportsCustomTypes shouldBe false
        caps.supportsPartitioning shouldBe true
    }

    test("DialectCapabilities.forDialect returns correct capabilities for SQLite") {
        val caps = DialectCapabilities.forDialect(DatabaseDialect.SQLITE)
        caps.supportsViews shouldBe true
        caps.supportsFunctions shouldBe false
        caps.supportsProcedures shouldBe false
        caps.supportsTriggers shouldBe true
        caps.supportsSequences shouldBe false
        caps.supportsCustomTypes shouldBe false
        caps.supportsPartitioning shouldBe false
    }

    test("DialectCapabilities data class equality") {
        val a = DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL)
        val b = DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL)
        a shouldBe b
    }

    test("DialectCapabilities data class inequality across dialects") {
        val pg = DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL)
        val my = DialectCapabilities.forDialect(DatabaseDialect.MYSQL)
        pg shouldNotBe my
    }

    test("DialectCapabilities copy changes field") {
        val caps = DialectCapabilities.forDialect(DatabaseDialect.POSTGRESQL)
        val modified = caps.copy(supportsRoutineRewrite = true)
        modified.supportsRoutineRewrite shouldBe true
        modified shouldNotBe caps
    }

    test("DialectCapabilities toString contains class name") {
        val caps = DialectCapabilities.forDialect(DatabaseDialect.SQLITE)
        caps.toString() shouldContain "DialectCapabilities"
    }

    // ── DataExportFormat enum ─────────────────────────────────────────

    test("DataExportFormat has three entries") {
        DataExportFormat.entries.map { it.name } shouldBe listOf("JSON", "YAML", "CSV")
    }

    test("DataExportFormat valueOf round-trips") {
        DataExportFormat.valueOf("JSON") shouldBe DataExportFormat.JSON
        DataExportFormat.valueOf("YAML") shouldBe DataExportFormat.YAML
        DataExportFormat.valueOf("CSV") shouldBe DataExportFormat.CSV
    }

    test("DataExportFormat cliName properties") {
        DataExportFormat.JSON.cliName shouldBe "json"
        DataExportFormat.YAML.cliName shouldBe "yaml"
        DataExportFormat.CSV.cliName shouldBe "csv"
    }

    test("DataExportFormat fileExtensions properties") {
        DataExportFormat.JSON.fileExtensions shouldBe listOf("json")
        DataExportFormat.YAML.fileExtensions shouldBe listOf("yaml", "yml")
        DataExportFormat.CSV.fileExtensions shouldBe listOf("csv")
    }

    test("DataExportFormat.fromCli resolves all known names") {
        DataExportFormat.fromCli("json") shouldBe DataExportFormat.JSON
        DataExportFormat.fromCli("yaml") shouldBe DataExportFormat.YAML
        DataExportFormat.fromCli("csv") shouldBe DataExportFormat.CSV
    }

    test("DataExportFormat.fromCli is case-insensitive") {
        DataExportFormat.fromCli("JSON") shouldBe DataExportFormat.JSON
        DataExportFormat.fromCli("Yaml") shouldBe DataExportFormat.YAML
        DataExportFormat.fromCli("CSV") shouldBe DataExportFormat.CSV
    }

    test("DataExportFormat.fromCli throws on unknown format") {
        val ex = shouldThrow<IllegalArgumentException> {
            DataExportFormat.fromCli("xml")
        }
        ex.message!! shouldContain "Unknown export format"
        ex.message!! shouldContain "xml"
    }
})
