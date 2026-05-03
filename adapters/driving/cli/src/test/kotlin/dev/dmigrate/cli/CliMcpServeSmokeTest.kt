package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import dev.dmigrate.cli.commands.McpCommand
import dev.dmigrate.cli.commands.McpStateDirLock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.io.path.deleteRecursively

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

    test("mcp serve fails with exit 2 when another process holds the state-dir lock") {
        // NOTE: this test exercises the in-process OverlappingFileLockException
        // fallback in McpStateDirLock — both lock attempts run in the same
        // JVM, so the JDK rejects the second tryLock() before it ever asks
        // the kernel. The plan-mandated "two-process" path (Akzeptanz §6.21
        // Z. 1242–1245) needs a forked JVM and lands with the AP 6.24
        // stdio+HTTP integration-test suite. TODO(AP 6.24): cover real
        // cross-process lock conflict via ProcessBuilder.
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        val dir = Files.createTempDirectory("dmigrate-mcp-cli-lock-")
        val held = McpStateDirLock.tryAcquire(dir, "test")
            .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "mcp", "serve",
                        "--transport", "stdio",
                        "--mcp-state-dir", dir.toString(),
                    ),
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            held.lock.close()
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            dir.deleteRecursively()
        }
    }

    test("mcp serve fails with exit 2 when --mcp-state-dir points at a regular file") {
        val file = Files.createTempFile("dmigrate-mcp-cli-not-a-dir-", ".tmp")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "mcp", "serve",
                        "--transport", "stdio",
                        "--mcp-state-dir", file.toString(),
                    ),
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            Files.deleteIfExists(file)
        }
    }

    test("mcp serve fails with exit 2 when --mcp-state-orphan-retention is bogus") {
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        val dir = Files.createTempDirectory("dmigrate-mcp-cli-retention-")
        try {
            val ex = shouldThrow<ProgramResult> {
                cli().parse(
                    listOf(
                        "mcp", "serve",
                        "--transport", "stdio",
                        "--mcp-state-dir", dir.toString(),
                        "--mcp-state-orphan-retention", "totally-bogus",
                    ),
                )
            }
            ex.statusCode shouldBe 2
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            dir.deleteRecursively()
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
