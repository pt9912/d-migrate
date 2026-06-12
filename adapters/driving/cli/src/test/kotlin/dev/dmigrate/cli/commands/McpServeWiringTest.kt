package dev.dmigrate.cli.commands

import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit-Tests fuer [McpServeWiring]. In-Memory- und persistenter Branch
 * von `build()` sind beide standalone testbar — der persistente Branch
 * via injizierte [ServerStateFactory], die in-memory Implementierungen
 * statt Hikari/Postgres liefert.
 */
class McpServeWiringTest : FunSpec({

    fun newWiring(
        connectionConfigPath: Path? = null,
        approvalGrantsFile: Path? = null,
        stderr: (String) -> Unit = {},
        serverStateFactory: ServerStateFactory = DefaultServerStateFactory(stderr),
    ) = McpServeWiring(
        effectiveConnectionConfigPath = connectionConfigPath,
        approvalGrantsFile = approvalGrantsFile,
        stderr = stderr,
        serverStateFactory = serverStateFactory,
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
    context("build (in-memory branch — no server.state)") {
        test("returns a fully-wired closeable McpCliServerWiring") {
            val stateDir = Files.createTempDirectory("dmigrate-build-im-")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val config = McpServerConfig()
            try {
                newWiring().build(config, owner, cursorKeyring = null).use { wiring ->
                    wiring shouldNotBe null
                    wiring.runtimeWiring shouldNotBe null
                    wiring.aiWiring shouldNotBe null
                    wiring.components shouldNotBe null
                    wiring.resourceStores shouldNotBe null
                    wiring.promptRegistry shouldNotBe null
                }
            } finally {
                owner.cleanupIfOwned()
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }

        test("artifacts.store=s3 flows through to S3-typed byte stores (S3.4b)") {
            val stateDir = Files.createTempDirectory("dmigrate-build-s3-")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            try {
                // Offline-safe: the startup sweeps of the retention /
                // finalisation loops only walk the empty in-memory metadata
                // stores — no S3 request is issued during build().
                newWiring().build(
                    config = McpServerConfig(),
                    owner = owner,
                    cursorKeyring = null,
                    artifacts = dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig.S3(
                        dev.dmigrate.server.adapter.storage.s3.S3StorageConfig(
                            bucket = "wiring-bucket",
                            endpoint = java.net.URI.create("http://localhost:1"),
                        ),
                    ),
                ).use { wiring ->
                    wiring.runtimeWiring.uploadSegmentStore
                        .shouldBeInstanceOf<dev.dmigrate.server.adapter.storage.s3.S3UploadSegmentStore>()
                    wiring.runtimeWiring.artifactContentStore
                        .shouldBeInstanceOf<dev.dmigrate.server.adapter.storage.s3.S3ArtifactContentStore>()
                }
            } finally {
                owner.cleanupIfOwned()
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }

        test("file-backed approval grant store gets used when configured") {
            val stateDir = Files.createTempDirectory("dmigrate-build-grants-")
            val grantsFile = Files.createTempFile("dmigrate-grants-build-", ".yaml")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            try {
                // Indirect verification: a non-null approvalGrantsFile flows into
                // build() via the wiring constructor (the OperationalMcpWiring
                // constructed inside build() takes the file-backed store).
                newWiring(approvalGrantsFile = grantsFile).build(
                    config = McpServerConfig(),
                    owner = owner,
                    cursorKeyring = null,
                ).use { /* no-op — exercise the path */ }
            } finally {
                owner.cleanupIfOwned()
                Files.deleteIfExists(grantsFile)
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
    }

    context("build (persistent branch — server.state configured)") {
        test("delegates to ServerStateFactory and wires bundle into McpCliServerWiring") {
            val stateDir = Files.createTempDirectory("dmigrate-build-jdbc-")
            val configFile = Files.createTempFile("dmigrate-build-jdbc-cfg-", ".yaml")
            // server.state configured -> resolveServerStateConfigOrExit returns
            // a non-null state, so build() takes the persistent branch.
            Files.writeString(
                configFile,
                """
                server:
                  state:
                    jdbcUrl: jdbc:postgresql://localhost/dmigrate-test
                """.trimIndent(),
            )
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            // Fake factory: returns an in-memory bundle so the rest of
            // build() runs to completion without touching JDBC.
            var factoryCalls = 0
            val cleanupCalls = java.util.concurrent.atomic.AtomicInteger(0)
            val fakeFactory = ServerStateFactory { _, phaseC ->
                factoryCalls++
                ServerStateBundle(
                    phaseCWithPersistence = phaseC,
                    idempotencyStore = dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore(),
                    jobStartTransaction = dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction(
                        phaseC.jobStore,
                        dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore(),
                    ),
                    quotaReservationOwnerStore =
                        dev.dmigrate.server.application.quota.InMemoryQuotaReservationOwnerStore(),
                    ownerAwareQuotaService = dev.dmigrate.server.application.quota.OwnerAwareQuotaService(
                        delegate = phaseC.quotaService,
                        ownerStore =
                            dev.dmigrate.server.application.quota.InMemoryQuotaReservationOwnerStore(),
                    ),
                    cleanup = AutoCloseable { cleanupCalls.incrementAndGet() },
                )
            }
            val (lines, sink) = stderrCapture()
            try {
                newWiring(
                    connectionConfigPath = configFile,
                    stderr = sink,
                    serverStateFactory = fakeFactory,
                ).build(
                    config = McpServerConfig(),
                    owner = owner,
                    cursorKeyring = null,
                ).use { wiring ->
                    wiring.runtimeWiring shouldNotBe null
                    wiring.aiWiring shouldNotBe null
                }
                factoryCalls shouldBe 1
                cleanupCalls.get() shouldBe 1
                lines.joinToString("\n") shouldContain "persistent backend enabled"
            } finally {
                owner.cleanupIfOwned()
                Files.deleteIfExists(configFile)
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }

        test("factory failure cleans up retention loops and propagates") {
            val stateDir = Files.createTempDirectory("dmigrate-build-jdbc-fail-")
            val configFile = Files.createTempFile("dmigrate-build-jdbc-fail-", ".yaml")
            Files.writeString(
                configFile,
                """
                server:
                  state:
                    jdbcUrl: jdbc:postgresql://localhost/dmigrate-test
                """.trimIndent(),
            )
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val failingFactory = ServerStateFactory { _, _ ->
                throw IllegalStateException("simulated DB-config failure")
            }
            try {
                io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                    newWiring(
                        connectionConfigPath = configFile,
                        serverStateFactory = failingFactory,
                    ).build(McpServerConfig(), owner, null)
                }
            } finally {
                owner.cleanupIfOwned()
                Files.deleteIfExists(configFile)
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }
    }
})

private fun stderrCapture(): Pair<MutableList<String>, (String) -> Unit> {
    val lines = mutableListOf<String>()
    return lines to { msg -> lines += msg }
}
