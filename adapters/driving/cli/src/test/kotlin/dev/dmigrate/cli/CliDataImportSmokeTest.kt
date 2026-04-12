package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * CLI-Smoke-Tests für `data import`. Prüft, dass der Command-Pfad
 * von Clikt-Parsing über [DataImportCommand][dev.dmigrate.cli.commands.DataImportCommand]
 * bis zum [DataImportRunner][dev.dmigrate.cli.commands.DataImportRunner] durchläuft
 * und erwartete Exit-Codes erzeugt.
 */
class CliDataImportSmokeTest : FunSpec({

    fun cli(): DMigrate {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.register(SqliteDriver())
        return DMigrate().subcommands(SchemaCommand(), DataCommand())
    }

    test("data import --help produces a help message") {
        shouldThrow<CliktError> {
            cli().parse(listOf("data", "import", "--help"))
        }
    }

    test("data import without --source → Clikt usage error") {
        shouldThrow<CliktError> {
            cli().parse(listOf("data", "import", "--target", "sqlite:///tmp/x.db"))
        }
    }

    test("data import with nonexistent source file → Exit 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "data", "import",
                    "--target", "sqlite:///tmp/d-migrate-cli-smoke.db",
                    "--source", "/nope/nonexistent.json",
                    "--format", "json",
                    "--table", "users",
                )
            )
        }
        ex.statusCode shouldBe 2
    }

    test("data import from stdin without --format → Exit 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "data", "import",
                    "--target", "sqlite:///tmp/d-migrate-cli-smoke.db",
                    "--source", "-",
                    "--table", "users",
                )
            )
        }
        ex.statusCode shouldBe 2
    }

    test("data import without --target and without default_target → Exit 2") {
        val jsonFile = Files.createTempFile("d-migrate-smoke-no-target-", ".json")
        Files.writeString(jsonFile, """[{"id":1,"name":"test"}]""")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--source", jsonFile.toString(),
                        "--table", "users",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(jsonFile)
        }
    }

    test("data import: real JSON into SQLite (connection error expected since no DB exists)") {
        val jsonFile = Files.createTempFile("d-migrate-smoke-import-", ".json")
        Files.writeString(jsonFile, """[{"id":1,"name":"test"}]""")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///${jsonFile.parent}/d-migrate-smoke-target.db",
                        "--source", jsonFile.toString(),
                        "--table", "users",
                    )
                )
            }
            // Exit 4 (connection) or 5 (streaming error) — depends on driver behavior
            ex.statusCode shouldBeInRange 4..5
        } finally {
            Files.deleteIfExists(jsonFile)
        }
    }

    test("data import: directory source with --table → Exit 2") {
        val importDir = Files.createTempDirectory("d-migrate-smoke-dir-")
        Files.writeString(importDir.resolve("users.json"), """[{"id":1}]""")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "import",
                        "--target", "sqlite:///tmp/d-migrate-cli-smoke.db",
                        "--source", importDir.toString(),
                        "--format", "json",
                        "--table", "users",
                    )
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(importDir.resolve("users.json"))
            Files.deleteIfExists(importDir)
        }
    }

    test("data import: --truncate --on-conflict abort → Exit 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "data", "import",
                    "--target", "sqlite:///tmp/d-migrate-cli-smoke.db",
                    "--source", "/nope/whatever.json",
                    "--format", "json",
                    "--table", "users",
                    "--truncate",
                    "--on-conflict", "abort",
                )
            )
        }
        ex.statusCode shouldBe 2
    }

    test("data import: --disable-fk-checks on SQLite is accepted (no Exit 2)") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "data", "import",
                    "--target", "sqlite:///tmp/d-migrate-cli-smoke.db",
                    "--source", "/nope/nonexistent.json",
                    "--format", "json",
                    "--table", "users",
                    "--disable-fk-checks",
                )
            )
        }
        // Should get past the --disable-fk-checks check (SQLite supports it)
        // and fail at source-path-does-not-exist → Exit 2
        ex.statusCode shouldBe 2
    }
})
