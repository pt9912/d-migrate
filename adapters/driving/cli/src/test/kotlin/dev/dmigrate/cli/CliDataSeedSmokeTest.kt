package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * CLI-Smoke-Tests für `data seed`. Prüft, dass der Command-Pfad von
 * Clikt-Parsing über [DataSeedCommand][dev.dmigrate.cli.commands.DataSeedCommand]
 * bis zum [DataSeedRunner][dev.dmigrate.cli.commands.DataSeedRunner]
 * durchläuft und erwartete Exit-Codes erzeugt (analog
 * `CliDataImportSmokeTest`, ImpPlan-1.3.0-cli-data-seed-p1.md AP5).
 */
class CliDataSeedSmokeTest : FunSpec({

    fun cli(): DMigrate {
        DatabaseDriverRegistry.clear()
        DatabaseDriverRegistry.register(SqliteDriver())
        return DMigrate().subcommands(DataCommand())
    }

    test("data seed --help produces a help message") {
        shouldThrow<CliktError> {
            cli().parse(listOf("data", "seed", "--help"))
        }
    }

    test("data seed without --schema → Clikt usage error") {
        shouldThrow<CliktError> {
            cli().parse(listOf("data", "seed", "--target", "sqlite:///tmp/x.db"))
        }
    }

    test("data seed with a nonexistent --schema file → Clikt usage error") {
        shouldThrow<CliktError> {
            cli().parse(
                listOf(
                    "data", "seed",
                    "--schema", "/nope/nonexistent-schema.yaml",
                    "--target", "sqlite:///tmp/x.db",
                ),
            )
        }
    }

    test("data seed with an unknown --locale → Exit 7") {
        val schemaFile = Files.createTempFile("d-migrate-seed-smoke-", ".yaml")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "seed",
                        "--schema", schemaFile.toString(),
                        "--target", "sqlite:///tmp/d-migrate-seed-smoke.db",
                        "--locale", "fr",
                    ),
                )
            }
            ex.statusCode shouldBe 7
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    test("data seed with an unreadable schema file → Exit 7") {
        val schemaFile = Files.createTempFile("d-migrate-seed-smoke-empty-", ".yaml")
        Files.writeString(schemaFile, "not: [valid, schema")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "seed",
                        "--schema", schemaFile.toString(),
                        "--target", "sqlite:///tmp/d-migrate-seed-smoke.db",
                    ),
                )
            }
            ex.statusCode shouldBe 7
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    test("data seed with a nonexistent --rules file → Clikt usage error (P2)") {
        val schemaFile = Files.createTempFile("d-migrate-seed-smoke-", ".yaml")
        try {
            shouldThrow<CliktError> {
                cli().parse(
                    listOf(
                        "data", "seed",
                        "--schema", schemaFile.toString(),
                        "--target", "sqlite:///tmp/x.db",
                        "--rules", "/nope/nonexistent-rules.yaml",
                    ),
                )
            }
        } finally {
            Files.deleteIfExists(schemaFile)
        }
    }

    test("data seed with an invalid --rules file → Exit 7 (P2)") {
        val schemaFile = Files.createTempFile("d-migrate-seed-smoke-", ".yaml")
        val rulesFile = Files.createTempFile("d-migrate-seed-smoke-rules-", ".yaml")
        Files.writeString(rulesFile, "notRules: []\n")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "data", "seed",
                        "--schema", schemaFile.toString(),
                        "--target", "sqlite:///tmp/d-migrate-seed-smoke.db",
                        "--rules", rulesFile.toString(),
                    ),
                )
            }
            ex.statusCode shouldBe 7
        } finally {
            Files.deleteIfExists(schemaFile)
            Files.deleteIfExists(rulesFile)
        }
    }
})
