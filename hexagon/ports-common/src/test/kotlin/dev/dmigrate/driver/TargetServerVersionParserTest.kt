package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class TargetServerVersionParserTest : FunSpec({

    test("PostgreSQL accepts a bare major, because that is how people name it") {
        val parsed = TargetServerVersionParser.parse(DatabaseDialect.POSTGRESQL, "16")
            .shouldBeInstanceOf<TargetVersionParse.Parsed>()

        parsed.version shouldBe PostgresServerVersion(16, 0)
    }

    test("PostgreSQL also accepts major.minor") {
        val parsed = TargetServerVersionParser.parse(DatabaseDialect.POSTGRESQL, " 16.4 ")
            .shouldBeInstanceOf<TargetVersionParse.Parsed>()

        parsed.version shouldBe PostgresServerVersion(16, 4)
    }

    test("MySQL keeps its three parts") {
        val parsed = TargetServerVersionParser.parse(DatabaseDialect.MYSQL, "8.0.16")
            .shouldBeInstanceOf<TargetVersionParse.Parsed>()

        parsed.version shouldBe MysqlServerVersion(8, 0, 16)
    }

    test("Oracle reads a release number, bare or full") {
        TargetServerVersionParser.parse(DatabaseDialect.ORACLE, "23")
            .shouldBeInstanceOf<TargetVersionParse.Parsed>().version
            .shouldBeInstanceOf<OracleServerVersion>().major shouldBe 23
        TargetServerVersionParser.parse(DatabaseDialect.ORACLE, "19.0.0.0.0")
            .shouldBeInstanceOf<TargetVersionParse.Parsed>().version
            .shouldBeInstanceOf<OracleServerVersion>().major shouldBe 19
    }

    test("an unreadable text names the shape that was expected") {
        val result = TargetServerVersionParser.parse(DatabaseDialect.POSTGRESQL, "latest")
            .shouldBeInstanceOf<TargetVersionParse.Unreadable>()

        result.expected shouldBe "a major version like 16, or major.minor like 16.4"
        TargetServerVersionParser.parse(DatabaseDialect.MYSQL, "8.0")
            .shouldBeInstanceOf<TargetVersionParse.Unreadable>()
    }

    test("SQL Server and SQLite have no version type yet, and say so instead of guessing") {
        TargetServerVersionParser.parse(DatabaseDialect.MSSQL, "2019") shouldBe TargetVersionParse.NotVersioned
        TargetServerVersionParser.parse(DatabaseDialect.SQLITE, "3.45") shouldBe TargetVersionParse.NotVersioned
    }
})
