package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.cursor.CursorKeyring
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig
import dev.dmigrate.server.adapter.storage.s3.S3StorageConfig
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

    context("parseArtifactsConfigOrExit (S3.4b)") {
        test("no connection-config path yields the file default") {
            newRunner().parseArtifactsConfigOrExit() shouldBe ArtifactStorageConfig.File
        }

        test("artifacts.store=s3 yields the parsed S3 config") {
            val configFile = Files.createTempFile("dmigrate-artifacts-s3-", ".yaml")
            Files.writeString(
                configFile,
                """
                artifacts:
                  store: s3
                  s3:
                    endpoint: "http://seaweed.invalid:8333"
                    bucket: "mcp-artifacts"
                """.trimIndent(),
            )
            try {
                val parsed = newRunner(effectivePath = configFile).parseArtifactsConfigOrExit()
                parsed.shouldBeInstanceOf<ArtifactStorageConfig.S3>()
                parsed.config.bucket shouldBe "mcp-artifacts"
            } finally {
                Files.deleteIfExists(configFile)
            }
        }

        test("malformed YAML exits 2 instead of leaking a raw snakeyaml exception (S3.4b-R1)") {
            val configFile = Files.createTempFile("dmigrate-artifacts-broken-", ".yaml")
            Files.writeString(configFile, "artifacts: [unclosed")
            val (lines, sink) = stderrCapture()
            try {
                val ex = shouldThrow<McpServeExit> {
                    newRunner(effectivePath = configFile, stderr = sink).parseArtifactsConfigOrExit()
                }
                ex.code shouldBe 2
                lines.shouldContain("MCP server configuration is invalid:")
            } finally {
                Files.deleteIfExists(configFile)
            }
        }

        test("invalid artifacts config (s3 without bucket) exits 2 with stderr message") {
            val configFile = Files.createTempFile("dmigrate-artifacts-bad-", ".yaml")
            Files.writeString(
                configFile,
                """
                artifacts:
                  store: s3
                  s3:
                    endpoint: "http://seaweed.invalid:8333"
                """.trimIndent(),
            )
            val (lines, sink) = stderrCapture()
            try {
                val ex = shouldThrow<McpServeExit> {
                    newRunner(effectivePath = configFile, stderr = sink).parseArtifactsConfigOrExit()
                }
                ex.code shouldBe 2
                lines.shouldContain("MCP server configuration is invalid:")
                lines.joinToString("\n") shouldContain "artifacts.s3.bucket is required"
            } finally {
                Files.deleteIfExists(configFile)
            }
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

        test("artifacts.store=s3 skips segment/artefact sweeps but keeps the assembly sweep (S3.4b)") {
            val dir = Files.createTempDirectory("dmigrate-sweep-s3-")
            // Stale local segment bytes: with store=s3 the state dir is not
            // the byte source, so the sweep MUST NOT touch them.
            val staleSegment = dir.resolve("segments/stale-session/0.bin")
            Files.createDirectories(staleSegment.parent)
            Files.writeString(staleSegment, "stale")
            val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
            val (lines, sink) = stderrCapture()
            try {
                newRunner(stderr = sink).runStartupSweepOrExit(
                    owner,
                    RetentionPolicy.Immediate,
                    ArtifactStorageConfig.S3(S3StorageConfig(bucket = "b")),
                )
                Files.exists(staleSegment) shouldBe true
                val joined = lines.joinToString("\n")
                joined shouldContain "segment/artefact sweeps skipped (artifacts.store=s3)"
                joined shouldContain "assembly spool(s)"
                joined shouldNotContain "upload-segment session(s)"
            } finally {
                runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    context("echoStartStateLine") {
        test("file default names file-backed byte content") {
            val dir = Files.createTempDirectory("dmigrate-stateline-file-")
            val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
            val (lines, sink) = stderrCapture()
            try {
                newRunner(stderr = sink).echoStartStateLine(owner)
                lines.joinToString("\n") shouldContain "byte content is file-backed"
            } finally {
                Files.deleteIfExists(dir)
            }
        }

        test("artifacts.store=s3 names endpoint and bucket, never credentials (S3.4b)") {
            val dir = Files.createTempDirectory("dmigrate-stateline-s3-")
            val owner = newRunner(McpServeOptions(mcpStateDir = dir)).resolveStateDirOrExit()
            val (lines, sink) = stderrCapture()
            try {
                newRunner(stderr = sink).echoStartStateLine(
                    owner,
                    ArtifactStorageConfig.S3(
                        S3StorageConfig(
                            bucket = "mcp-artifacts",
                            endpoint = URI.create("http://seaweed.invalid:8333"),
                            accessKey = "super-secret-key",
                            secretKey = "super-secret-value",
                        ),
                    ),
                )
                val joined = lines.joinToString("\n")
                joined shouldContain "byte content is S3-backed"
                joined shouldContain "endpoint=http://seaweed.invalid:8333"
                joined shouldContain "bucket=mcp-artifacts"
                joined shouldNotContain "super-secret"
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

        test("execute exits 2 with state-dir-error when --mcp-state-dir points at a regular file") {
            // StateDirResolver returns the file unchanged (it doesn't
            // validate); StateDirValidator then refuses with "is not a
            // directory" → reportStateDirFailure prints + exits 2.
            val file = Files.createTempFile("dmigrate-runner-notadir-", ".tmp")
            try {
                val (lines, sink) = stderrCapture()
                val exit = newRunner(
                    McpServeOptions(
                        transport = "stdio",
                        mcpStateDir = file,
                    ),
                    stderr = sink,
                ).execute()
                exit shouldBe 2
                val joined = lines.joinToString("\n")
                joined shouldContain "MCP server configuration is invalid"
                joined shouldContain "is not a directory"
            } finally {
                Files.deleteIfExists(file)
            }
        }

        test("execute exits 2 with lock-conflict diagnostic when another lock holder is active") {
            val dir = Files.createTempDirectory("dmigrate-runner-lock-conflict-")
            // Pre-acquire the OS-level lock so the runner's acquireLockOrExit
            // path returns AcquireOutcome.Conflict.
            val holder = McpStateDirLock.tryAcquire(dir, "holder")
                .shouldBeInstanceOf<McpStateDirLock.AcquireOutcome.Acquired>()
            try {
                val (lines, sink) = stderrCapture()
                val exit = newRunner(
                    McpServeOptions(
                        transport = "stdio",
                        mcpStateDir = dir,
                    ),
                    stderr = sink,
                ).execute()
                exit shouldBe 2
                val joined = lines.joinToString("\n")
                joined shouldContain "MCP server cannot start"
                joined shouldContain "another `mcp serve` is active"
            } finally {
                holder.lock.close()
                runCatching { Files.walk(dir).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }
})
