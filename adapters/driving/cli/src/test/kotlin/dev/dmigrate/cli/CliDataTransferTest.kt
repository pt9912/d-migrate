package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.MissingOption
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.DataCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * CLI-level tests for `data transfer`.
 *
 * These test the Clikt command wiring: argument parsing, flag defaults,
 * and error mapping to exit codes. Runner-level logic (preflight, FK
 * ordering, scrubbing) is covered by [DataTransferRunnerTest].
 */
class CliDataTransferTest : FunSpec({

    fun cli() = DMigrate().subcommands(DataCommand())

    fun captureStreams(block: () -> Unit): String {
        val origErr = System.err
        val capture = ByteArrayOutputStream()
        System.setErr(PrintStream(capture, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setErr(origErr)
        }
        return capture.toString(Charsets.UTF_8)
    }

    // ── Help ────────────────────────────────────────────────────────

    test("data transfer --help is reachable") {
        shouldThrow<CliktError> {
            cli().parse(listOf("data", "transfer", "--help"))
        }
    }

    // ── Missing required options ────────────────────────────────────

    test("missing --source produces usage error") {
        shouldThrow<MissingOption> {
            cli().parse(listOf("data", "transfer", "--target", "tgt"))
        }
    }

    test("missing --target produces usage error") {
        shouldThrow<MissingOption> {
            cli().parse(listOf("data", "transfer", "--source", "src"))
        }
    }

    // ── Unresolvable source/target → exit 7 ────────────────────────

    test("unresolvable source alias exits with code 7") {
        captureStreams {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf("data", "transfer",
                    "--source", "nonexistent_source",
                    "--target", "nonexistent_target"))
            }
            ex.statusCode shouldBe 7
        }
    }

    // ── Flag validation → exit 2 ───────────────────────────────────

    test("--since without --since-column exits with code 2") {
        captureStreams {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf("data", "transfer",
                    "--source", "src",
                    "--target", "tgt",
                    "--since", "2024-01-01"))
            }
            ex.statusCode shouldBe 2
        }
    }

    test("--since-column without --since exits with code 2") {
        captureStreams {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf("data", "transfer",
                    "--source", "src",
                    "--target", "tgt",
                    "--since-column", "updated_at"))
            }
            ex.statusCode shouldBe 2
        }
    }

    test("unknown --on-conflict value exits with code 2") {
        captureStreams {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf("data", "transfer",
                    "--source", "src",
                    "--target", "tgt",
                    "--on-conflict", "invalid_mode"))
            }
            ex.statusCode shouldBe 2
        }
    }

    test("unknown --trigger-mode value exits with code 2") {
        captureStreams {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(listOf("data", "transfer",
                    "--source", "src",
                    "--target", "tgt",
                    "--trigger-mode", "nope"))
            }
            ex.statusCode shouldBe 2
        }
    }

    // ── Default flag wiring ────────────────────────────────────────

    test("--tables flag accepts comma-separated list") {
        // Should not throw MissingOption; actual execution fails at
        // resolution (exit 7) but flag parsing succeeds
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("data", "transfer",
                "--source", "src",
                "--target", "tgt",
                "--tables", "users,orders,products"))
        }
        // Flag parsed → reaches runner → fails at resolution → exit 7
        ex.statusCode shouldBe 7
    }

    test("--truncate flag is accepted") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("data", "transfer",
                "--source", "src",
                "--target", "tgt",
                "--truncate"))
        }
        ex.statusCode shouldBe 7
    }

    test("--chunk-size flag accepts integer") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(listOf("data", "transfer",
                "--source", "src",
                "--target", "tgt",
                "--chunk-size", "5000"))
        }
        ex.statusCode shouldBe 7
    }
})
