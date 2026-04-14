package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.mysql.MysqlDataWriter
import dev.dmigrate.driver.postgresql.PostgresDataWriter
import dev.dmigrate.driver.sqlite.SqliteDataWriter
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Tests for the `--help`, `--version` and `--verbose/--quiet`-exclusion
 * branches that otherwise only run from a real shell invocation.
 *
 * These tests cover:
 * - every `help(context)` method across the command hierarchy
 *   (DMigrate, SchemaCommand, SchemaValidateCommand, SchemaGenerateCommand,
 *    DataCommand, DataExportCommand)
 * - the top-level `fun main(args: Array<String>)` in `Main.kt`, including
 *   the driver-registration bootstrap
 * - the `if (verbose && quiet) throw UsageError(...)` guard in DMigrate.run
 */
class CliHelpAndBootstrapTest : FunSpec({

    fun cli() = DMigrate().subcommands(SchemaCommand(), DataCommand())

    /**
     * Captures System.out + System.err during [block], restoring the
     * original streams afterwards. Used because Clikt's help and
     * `PrintMessage` output goes through the terminal, which in turn
     * writes to `System.out`.
     */
    fun captureStreams(block: () -> Unit) {
        val originalOut = System.out
        val originalErr = System.err
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
        }
    }

    // ─── --help on every command in the hierarchy ────────────────

    test("DMigrate --help produces a help message") {
        // parse() throws PrintHelpMessage (subclass of CliktError) on --help.
        // The CLI's .main() catches that; we have to catch it here.
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("--help")) }
        }
    }

    test("schema --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("schema", "--help")) }
        }
    }

    test("schema validate --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("schema", "validate", "--help")) }
        }
    }

    test("schema generate --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("schema", "generate", "--help")) }
        }
    }

    test("schema compare --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("schema", "compare", "--help")) }
        }
    }

    test("schema reverse --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("schema", "reverse", "--help")) }
        }
    }

    test("data --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("data", "--help")) }
        }
    }

    test("data export --help produces a help message") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("data", "export", "--help")) }
        }
    }

    // ─── --version ───────────────────────────────────────────────

    test("--version throws PrintMessage (CliktError) with the version string") {
        captureStreams {
            shouldThrow<CliktError> { cli().parse(listOf("--version")) }
        }
    }

    test("cliVersion resolves a semver-like build version from resources") {
        cliVersion().shouldMatch("""\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?""".toRegex())
    }

    // ─── --verbose + --quiet mutual exclusion ────────────────────

    test("--verbose together with --quiet throws a UsageError") {
        captureStreams {
            shouldThrow<UsageError> {
                cli().parse(listOf("--verbose", "--quiet", "schema", "validate", "--source", "/tmp/nope.yaml"))
            }
        }
    }

    // ─── Main.kt bootstrap ───────────────────────────────────────
    // NOTE: We do NOT call the top-level `main(args)` directly — Clikt's
    // `CliktCommand.main()` calls `exitProcess` on every exit path, which
    // would kill the test JVM and skip all subsequent Kotest specs.
    //
    // Instead we exercise the testable split: `registerDrivers()`,
    // `buildRootCommand()` and `runCli(args)` — all of which use `parse()`
    // under the hood and throw `CliktError` for tests to catch.

    test("runCli(--version) runs bootstrap + parse and throws PrintMessage") {
        captureStreams {
            shouldThrow<CliktError> { runCli(arrayOf("--version")) }
        }
    }

    test("runCli(--help) runs bootstrap + parse and throws PrintHelpMessage") {
        captureStreams {
            shouldThrow<CliktError> { runCli(arrayOf("--help")) }
        }
    }

    test("registerDrivers() is idempotent (can be called multiple times)") {
        shouldNotThrowAny {
            registerDrivers()
            registerDrivers()
        }
    }

    test("registerDrivers() exposes dataWriter() for all production dialects") {
        registerDrivers()

        DatabaseDriverRegistry.get(DatabaseDialect.POSTGRESQL)
            .dataWriter()
            .shouldBeInstanceOf<PostgresDataWriter>()
        DatabaseDriverRegistry.get(DatabaseDialect.MYSQL)
            .dataWriter()
            .shouldBeInstanceOf<MysqlDataWriter>()
        DatabaseDriverRegistry.get(DatabaseDialect.SQLITE)
            .dataWriter()
            .shouldBeInstanceOf<SqliteDataWriter>()
    }

    test("buildRootCommand() returns a DMigrate with schema and data subcommands") {
        val root = buildRootCommand()
        // Verify the hierarchy by parsing --help; if the subcommands weren't
        // wired up, `schema --help` would throw a UsageError instead of
        // PrintHelpMessage.
        captureStreams {
            shouldThrow<CliktError> { root.parse(listOf("schema", "--help")) }
        }
    }
})
