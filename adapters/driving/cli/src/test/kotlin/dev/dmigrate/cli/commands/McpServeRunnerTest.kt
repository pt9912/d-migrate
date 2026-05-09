package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Unit-Tests fuer [McpServeRunner]. Greift auf interne Hilfsmethoden zu
 * (gleiches Modul). Klikt-Framework wird nicht aktiviert — der Runner
 * ist absichtlich Framework-frei.
 */
class McpServeRunnerTest : FunSpec({

    fun stderrCapture(): Pair<MutableList<String>, (String) -> Unit> {
        val lines = mutableListOf<String>()
        return lines to { msg -> lines += msg }
    }

    fun newRunner(
        options: McpServeOptions = McpServeOptions(),
        stderr: (String) -> Unit = {},
        effectivePath: Path? = null,
    ) = McpServeRunner(
        options = options,
        stderr = stderr,
        effectiveConnectionConfigPath = effectivePath,
        cliVersionProvider = { "test" },
    )

    context("buildConfig") {
        test("default options yield default-loopback origins, JWT_JWKS auth, default port 0") {
            val cfg = newRunner().buildConfig()
            cfg.bindAddress shouldBe "127.0.0.1"
            cfg.port shouldBe 0
            cfg.publicBaseUrl shouldBe null
            cfg.allowedOrigins shouldBe McpServerConfig.DEFAULT_LOOPBACK_ORIGINS
            cfg.authMode shouldBe AuthMode.JWT_JWKS
            cfg.issuer shouldBe null
            cfg.jwksUrl shouldBe null
            cfg.introspectionUrl shouldBe null
            cfg.audience shouldBe null
            cfg.operationTimeout shouldBe Duration.ofSeconds(McpServerConfig.DEFAULT_OPERATION_TIMEOUT.toSeconds())
        }

        test("custom origins replace default-loopback set") {
            val cfg = newRunner(McpServeOptions(allowOrigin = listOf("https://a", "https://b"))).buildConfig()
            cfg.allowedOrigins shouldBe setOf("https://a", "https://b")
        }

        test("publicBaseUrl + issuer + jwksUrl + introspectionUrl get URI-parsed") {
            val cfg = newRunner(
                McpServeOptions(
                    publicBaseUrl = "https://example.invalid/mcp",
                    issuer = "https://issuer.invalid/",
                    jwksUrl = "https://jwks.invalid/keys",
                    introspectionUrl = "https://introspect.invalid/",
                ),
            ).buildConfig()
            cfg.publicBaseUrl shouldBe URI.create("https://example.invalid/mcp")
            cfg.issuer shouldBe URI.create("https://issuer.invalid/")
            cfg.jwksUrl shouldBe URI.create("https://jwks.invalid/keys")
            cfg.introspectionUrl shouldBe URI.create("https://introspect.invalid/")
        }

        test("auth-mode mapping: disabled / jwt-jwks / jwt-introspection") {
            newRunner(McpServeOptions(authMode = "disabled")).buildConfig().authMode shouldBe AuthMode.DISABLED
            newRunner(McpServeOptions(authMode = "jwt-jwks")).buildConfig().authMode shouldBe AuthMode.JWT_JWKS
            newRunner(McpServeOptions(authMode = "jwt-introspection")).buildConfig().authMode shouldBe AuthMode.JWT_INTROSPECTION
        }

        test("operationTimeoutSeconds drives operationTimeout") {
            newRunner(McpServeOptions(operationTimeoutSeconds = 42))
                .buildConfig().operationTimeout shouldBe Duration.ofSeconds(42)
        }
    }

    context("parseRetentionOrExit") {
        test("default (null) yields the default policy") {
            val policy = newRunner().parseRetentionOrExit()
            policy.shouldBeInstanceOf<RetentionPolicy.After>()
        }

        test("'never' yields RetentionPolicy.Never") {
            val policy = newRunner(McpServeOptions(mcpStateOrphanRetention = "never")).parseRetentionOrExit()
            policy.shouldBeInstanceOf<RetentionPolicy.Never>()
        }

        test("'0' yields RetentionPolicy.Immediate") {
            val policy = newRunner(McpServeOptions(mcpStateOrphanRetention = "0")).parseRetentionOrExit()
            policy.shouldBeInstanceOf<RetentionPolicy.Immediate>()
        }

        test("invalid retention value exits 2 with stderr message") {
            val (lines, sink) = stderrCapture()
            val ex = shouldThrow<McpServeExit> {
                newRunner(
                    McpServeOptions(mcpStateOrphanRetention = "definitely-not-a-duration"),
                    stderr = sink,
                ).parseRetentionOrExit()
            }
            ex.code shouldBe 2
            lines.shouldContain("MCP server configuration is invalid:")
            lines.any { it.startsWith("  - ") } shouldBe true
        }
    }

    context("parseCursorKeyringOrExit") {
        test("returns null when no keyring file is configured") {
            newRunner().parseCursorKeyringOrExit() shouldBe null
        }

        test("loads a valid keyring file") {
            val tempFile = Files.createTempFile("dmigrate-keyring-", ".yaml")
            Files.writeString(tempFile, McpCursorKeyringConfig.renderSingleKeyFile("test-kid"))
            try {
                val keyring = newRunner(McpServeOptions(cursorKeyringFile = tempFile)).parseCursorKeyringOrExit()
                keyring.shouldBeInstanceOf<CursorKeyring>()
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }

        test("invalid keyring file exits 2") {
            val tempFile = Files.createTempFile("dmigrate-keyring-bad-", ".yaml")
            Files.writeString(tempFile, "this: is not: a: valid: keyring")
            val (lines, sink) = stderrCapture()
            try {
                val ex = shouldThrow<McpServeExit> {
                    newRunner(
                        McpServeOptions(cursorKeyringFile = tempFile),
                        stderr = sink,
                    ).parseCursorKeyringOrExit()
                }
                ex.code shouldBe 2
                lines.shouldContain("MCP server configuration is invalid:")
            } finally {
                Files.deleteIfExists(tempFile)
            }
        }
    }

    context("rejectDevKeyringInProductionOrExit") {
        test("non-null keyring always passes") {
            val keyring = McpCursorKeyringConfig.load(
                Files.createTempFile("dmigrate-keyring-pass-", ".yaml").also {
                    Files.writeString(it, McpCursorKeyringConfig.renderSingleKeyFile("k"))
                },
            )
            // Should not throw regardless of transport / authMode
            newRunner(McpServeOptions(transport = "http", authMode = "jwt-jwks"))
                .rejectDevKeyringInProductionOrExit(keyring)
        }

        test("stdio + null keyring is allowed (dev path)") {
            newRunner(McpServeOptions(transport = "stdio", authMode = "jwt-jwks"))
                .rejectDevKeyringInProductionOrExit(null)
        }

        test("http + disabled auth + null keyring is allowed (loopback dev)") {
            newRunner(McpServeOptions(transport = "http", authMode = "disabled"))
                .rejectDevKeyringInProductionOrExit(null)
        }

        test("http + jwt-jwks + null keyring exits 2") {
            val (lines, sink) = stderrCapture()
            val ex = shouldThrow<McpServeExit> {
                newRunner(
                    McpServeOptions(transport = "http", authMode = "jwt-jwks"),
                    stderr = sink,
                ).rejectDevKeyringInProductionOrExit(null)
            }
            ex.code shouldBe 2
            lines.joinToString("\n") shouldContain "--cursor-keyring-file is required"
        }

        test("http + jwt-introspection + null keyring exits 2") {
            val (_, sink) = stderrCapture()
            shouldThrow<McpServeExit> {
                newRunner(
                    McpServeOptions(transport = "http", authMode = "jwt-introspection"),
                    stderr = sink,
                ).rejectDevKeyringInProductionOrExit(null)
            }.code shouldBe 2
        }
    }

    context("approvalGrantStore") {
        test("null option yields InMemoryApprovalGrantStore") {
            newRunner().approvalGrantStore().shouldBeInstanceOf<InMemoryApprovalGrantStore>()
        }

        test("file option yields FileBackedApprovalGrantStore") {
            val file = Files.createTempFile("dmigrate-grants-", ".yaml")
            try {
                val store = newRunner(McpServeOptions(approvalGrantsFile = file)).approvalGrantStore()
                store.shouldBeInstanceOf<FileBackedApprovalGrantStore>()
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    context("resolveStateDirOrExit") {
        test("falls back to a CLI-owned tempdir when no option is set") {
            val owner = newRunner().resolveStateDirOrExit()
            owner.resolved.owned shouldBe true
            owner.cleanupIfOwned()
        }

        test("uses operator-supplied dir when set") {
            val dir = Files.createTempDirectory("dmigrate-runner-state-")
            try {
                val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
                owner.resolved.path shouldBe dir
                owner.resolved.owned shouldBe false
            } finally {
                runCatching { Files.deleteIfExists(dir) }
            }
        }
    }

    context("runStartupSweepOrExit") {
        test("Never policy short-circuits with a single stderr line") {
            val dir = Files.createTempDirectory("dmigrate-sweep-never-")
            val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
            val (lines, sink) = stderrCapture()
            try {
                newRunner(stderr = sink).runStartupSweepOrExit(owner, RetentionPolicy.Never)
                lines.size shouldBe 1
                lines[0] shouldContain "MCP startup sweep skipped (retention=never)"
            } finally {
                Files.deleteIfExists(dir)
            }
        }

        test("Immediate policy on empty dir reports zeroes") {
            val dir = Files.createTempDirectory("dmigrate-sweep-immediate-")
            val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
            val (lines, sink) = stderrCapture()
            try {
                newRunner(stderr = sink).runStartupSweepOrExit(owner, RetentionPolicy.Immediate)
                val joined = lines.joinToString("\n")
                joined shouldContain "removed 0 upload-segment session(s)"
                joined shouldContain "0 artefact file(s)"
                joined shouldContain "0 assembly spool(s)"
                joined shouldNotContain "skipped"
            } finally {
                Files.deleteIfExists(dir)
            }
        }

        test("After policy on empty dir reports zeroes too") {
            val dir = Files.createTempDirectory("dmigrate-sweep-after-")
            val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
            val (lines, sink) = stderrCapture()
            try {
                newRunner(stderr = sink).runStartupSweepOrExit(
                    owner,
                    RetentionPolicy.After(Duration.ofHours(1)),
                )
                val joined = lines.joinToString("\n")
                joined shouldContain "removed 0 upload-segment session(s)"
            } finally {
                Files.deleteIfExists(dir)
            }
        }
    }

    context("execute") {
        test("invalid auth-mode combination produces validation errors and exits 2") {
            val (lines, sink) = stderrCapture()
            // jwt-jwks transport=http requires issuer + jwksUrl + audience.
            // Missing all of these triggers config validation errors.
            val exit = newRunner(
                McpServeOptions(
                    transport = "http",
                    authMode = "jwt-jwks",
                ),
                stderr = sink,
            ).execute()
            exit shouldBe 2
            lines.joinToString("\n") shouldContain "MCP server configuration is invalid"
        }
    }
})
