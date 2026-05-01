package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.McpCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Smoke tests for `d-migrate mcp serve` per `ImpPlan-0.9.6-B.md` §6.11.
 * Covers the help output and the configuration-error exit path. We do
 * NOT actually start the server here — that would block the test on
 * stdin/SIGINT.
 */
class CliMcpServeSmokeTest : FunSpec({

    fun cli(): DMigrate = DMigrate().subcommands(McpCommand())

    test("mcp --help produces a help message") {
        shouldThrow<CliktError> {
            cli().parse(listOf("mcp", "--help"))
        }
    }

    test("mcp serve --help produces a help message") {
        shouldThrow<CliktError> {
            cli().parse(listOf("mcp", "serve", "--help"))
        }
    }

    test("mcp serve with bind=0.0.0.0 + auth-mode=disabled fails with exit 2") {
        // §12.12: AuthMode.DISABLED is loopback-only. The bootstrap
        // refuses to start in this combination — the CLI surfaces it
        // as ProgramResult(2) before any network bind happens.
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "mcp", "serve",
                    "--transport", "http",
                    "--bind", "0.0.0.0",
                    "--auth-mode", "disabled",
                ),
            )
        }
        ex.statusCode shouldBe 2
    }

    test("mcp serve with auth-mode=jwt-jwks but missing issuer/jwks/audience fails with exit 2") {
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "mcp", "serve",
                    "--transport", "http",
                    "--auth-mode", "jwt-jwks",
                ),
            )
        }
        ex.statusCode shouldBe 2
    }

    test("mcp serve rejects unknown --transport value at parse time") {
        shouldThrow<CliktError> {
            cli().parse(listOf("mcp", "serve", "--transport", "ftp"))
        }
    }

    test("mcp serve --transport stdio with default-config does NOT fail HTTP-only validation (§12.15)") {
        // §12.15: stdio ignores authMode entirely. The CLI must use
        // validateForStdio() instead of the HTTP validate() — otherwise
        // a default config (authMode=JWT_JWKS, no issuer/jwks/audience)
        // would refuse to start stdio with HTTP-shaped error messages.
        // We trigger a real stdio-relevant violation (unreadable
        // stdioTokenFile) and assert the failure surfaces THAT, not
        // the JWT-JWKS pflicht.
        val ex = shouldThrow<ProgramResult> {
            cli().parse(
                listOf(
                    "mcp", "serve",
                    "--transport", "stdio",
                    "--stdio-token-file", "/no/such/path-${System.nanoTime()}.json",
                ),
            )
        }
        ex.statusCode shouldBe 2
        // Note: the smoke test asserts the exit code only — the
        // stderr message containing "stdioTokenFile not readable"
        // (and NOT "JWT_JWKS requires …") is asserted by the
        // McpServerConfigStdioValidationTest in the mcp module.
    }
})
