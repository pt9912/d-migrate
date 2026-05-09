package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit-Tests fuer [McpServeWiring]. Schwerpunkt: die framework-frei
 * testbaren Helper (ApprovalGrantStore-Auswahl, DataSource-Aufbau,
 * server-state Config-Resolver). `build()` selbst ist nicht direkt
 * testbar weil es echte McpRuntimeWiring + Server-State-Adapter
 * verdrahtet — das passiert ueber Integrationstests in
 * `:test:integration-server-state`.
 */
class McpServeWiringTest : FunSpec({

    fun newWiring(
        connectionConfigPath: Path? = null,
        approvalGrantsFile: Path? = null,
        stderr: (String) -> Unit = {},
    ) = McpServeWiring(
        effectiveConnectionConfigPath = connectionConfigPath,
        approvalGrantsFile = approvalGrantsFile,
        stderr = stderr,
    )

    context("approvalGrantStore") {
        test("null file yields in-memory store") {
            newWiring().approvalGrantStore().shouldBeInstanceOf<InMemoryApprovalGrantStore>()
        }

        test("non-null file yields file-backed store") {
            val file = Files.createTempFile("dmigrate-wiring-grants-", ".yaml")
            try {
                newWiring(approvalGrantsFile = file)
                    .approvalGrantStore()
                    .shouldBeInstanceOf<FileBackedApprovalGrantStore>()
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    // createServerStateDataSource is exercised end-to-end by the
    // integration tests in :test:integration-server-state, where a real
    // Postgres lives — Hikari validates the connection during pool
    // construction (default `initializationFailTimeout=1ms`), so a
    // standalone unit test would need an embedded JDBC driver on the
    // CLI classpath just to verify the HikariConfig wiring.

    context("resolveServerStateConfigOrExit") {
        test("returns null when no connection-config file points to server.state") {
            // Without a config file, McpServerStateConfigResolver returns null.
            newWiring().resolveServerStateConfigOrExit() shouldBe null
        }

        test("invalid server.state config exits 2 with stderr message") {
            val configFile = Files.createTempFile("dmigrate-wiring-bad-config-", ".yaml")
            // server.state present but maximumPoolSize is non-numeric — resolver
            // throws McpServerStateConfigError, runner maps it to exit 2.
            Files.writeString(
                configFile,
                """
                server:
                  state:
                    jdbcUrl: jdbc:postgresql://localhost/dmigrate
                    hikari:
                      maximumPoolSize: not-a-number
                """.trimIndent(),
            )
            val (lines, sink) = stderrCapture()
            try {
                val ex = io.kotest.assertions.throwables.shouldThrow<McpServeExit> {
                    newWiring(connectionConfigPath = configFile, stderr = sink)
                        .resolveServerStateConfigOrExit()
                }
                ex.code shouldBe 2
                lines.joinToString("\n").let { joined ->
                    joined.contains("MCP server configuration is invalid") shouldBe true
                }
            } finally {
                Files.deleteIfExists(configFile)
            }
        }
    }
})

private fun stderrCapture(): Pair<MutableList<String>, (String) -> Unit> {
    val lines = mutableListOf<String>()
    return lines to { msg -> lines += msg }
}
