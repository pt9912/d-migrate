package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.sqlite.SqliteDriver
import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.mcp.registry.McpRuntimeWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.adapter.storage.s3.ArtifactStorageConfig
import dev.dmigrate.server.adapter.storage.s3.S3ArtifactContentStore
import dev.dmigrate.server.adapter.storage.s3.S3StorageConfig
import dev.dmigrate.server.adapter.storage.s3.S3UploadSegmentStore
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.ResolvedConnection
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Instant

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
        policyFile: Path? = null,
        stderr: (String) -> Unit = {},
        serverStateFactory: ServerStateFactory = DefaultServerStateFactory(stderr),
    ) = McpServeWiring(
        effectiveConnectionConfigPath = connectionConfigPath,
        approvalGrantsFile = approvalGrantsFile,
        policyFile = policyFile,
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
    context("Reverse-Praeferenzen der Lese-Jobs") {
        test("the job factory reads with the reverse block of the connection config") {
            DatabaseDriverRegistry.register(SqliteDriver())
            val dir = Files.createTempDirectory("dmigrate-wiring-reverse-prefs-")
            val db = dir.resolve("shop.db")
            DriverManager.getConnection("jdbc:sqlite:$db").use { conn ->
                conn.createStatement().use { it.execute("CREATE TABLE t (id INTEGER PRIMARY KEY AUTOINCREMENT)") }
            }
            val tenant = TenantId("acme")
            val owner = PrincipalId("alice")
            val connectionStore = InMemoryConnectionReferenceStore().apply {
                save(
                    ConnectionReference(
                        connectionId = "c1", tenantId = tenant, displayName = "sqlite", dialectId = "sqlite",
                        sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                        resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, "c1"),
                        credentialRef = "stub:cred",
                    ),
                )
            }
            val artifactStore = InMemoryArtifactStore()
            val contentStore = InMemoryArtifactContentStore()
            val phaseC = McpRuntimeWiring(
                uploadSessionStore = InMemoryUploadSessionStore(),
                uploadSegmentStore = InMemoryUploadSegmentStore(),
                artifactStore = artifactStore,
                artifactContentStore = contentStore,
                schemaStore = InMemorySchemaStore(),
                jobStore = InMemoryJobStore(),
                quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
                limits = McpLimitsConfig(),
                clock = Clock.systemUTC(),
                connectionStore = connectionStore,
            )
            val resolver = object : ConnectionSecretResolver {
                override fun resolve(reference: ConnectionReference, principal: PrincipalContext): ResolvedConnection =
                    ResolvedConnection.Success("sqlite:$db")
            }
            fun reversed(config: String): String {
                val cfg = dir.resolve("cfg-${System.nanoTime()}.yaml").also { Files.writeString(it, config) }
                val record = Fixtures.jobRecord("job-reverse").let { job ->
                    job.copy(managedJob = job.managedJob.copy(operation = "schema_reverse"))
                }
                val request = JobStartRequest(
                    toolName = "schema_reverse_start", tenantId = tenant, callerId = owner,
                    idempotencyKey = "idem-${System.nanoTime()}", approvalToken = null,
                    payload = JsonValue.Obj(
                        linkedMapOf("connectionId" to JsonValue.Str("dmigrate://tenants/acme/connections/c1")),
                    ),
                    refs = emptyList(), now = Instant.now(), principalContext = Fixtures.principalContext(),
                    jobBuilder = { _, _ -> record },
                )
                val worker = newWiring(connectionConfigPath = cfg).mcpCoreJobWorkerFactory(phaseC, resolver)
                    .create(record, request)!!
                val outcome = worker.execute(record, CancellationTokenSource.create().token)
                val artifactId = (outcome as JobWorkerOutcome.Succeeded).artifactRefs.single().substringAfterLast('/')
                val size = artifactStore.findById(record.tenantId, artifactId)!!.managedArtifact.sizeBytes
                return contentStore.openRangeRead(artifactId, 0, size).readAllBytes().toString(Charsets.UTF_8)
            }

            reversed("reverse:\n  sqlite:\n    autoincrement_width: 64\n") shouldContain "legacy_serial_syntax: true"
            val declared = reversed(
                "reverse:\n  sqlite:\n    autoincrement_width: 64\n    autoincrement_syntax: identity\n",
            )
            declared shouldContain "type: identity"
            declared shouldNotContain "legacy_serial_syntax"
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
                    artifacts = ArtifactStorageConfig.S3(
                        S3StorageConfig(
                            bucket = "wiring-bucket",
                            endpoint = java.net.URI.create("http://localhost:1"),
                        ),
                    ),
                ).use { wiring ->
                    wiring.runtimeWiring.uploadSegmentStore
                        .shouldBeInstanceOf<S3UploadSegmentStore>()
                    wiring.runtimeWiring.artifactContentStore
                        .shouldBeInstanceOf<S3ArtifactContentStore>()
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

        test("invalid --policy-file exits 2 with stderr message (AE-A4)") {
            val stateDir = Files.createTempDirectory("dmigrate-build-policy-bad-")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val policyFile = Files.createTempFile("dmigrate-policy-bad-", ".yaml")
            Files.writeString(policyFile, "rules:\n  - effect: maybe\n")
            val messages = mutableListOf<String>()
            try {
                val ex = shouldThrow<McpServeExit> {
                    newWiring(policyFile = policyFile, stderr = { messages += it })
                        .build(McpServerConfig(), owner, cursorKeyring = null)
                }
                ex.code shouldBe 2
                messages.any { it.contains("effect") } shouldBe true
            } finally {
                owner.cleanupIfOwned()
                Files.deleteIfExists(policyFile)
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }

        test("--policy-file rules flow into OperationalMcpWiring.policyService") {
            val stateDir = Files.createTempDirectory("dmigrate-build-policy-ok-")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val policyFile = Files.createTempFile("dmigrate-policy-ok-", ".yaml")
            Files.writeString(
                policyFile,
                """
                rules:
                  - toolName: schema_reverse_start
                    effect: allow
                """.trimIndent(),
            )
            try {
                newWiring(policyFile = policyFile).build(McpServerConfig(), owner, cursorKeyring = null).use { wiring ->
                    val attempt = PolicyAttempt(
                        tenantId = TenantId("t1"),
                        callerId = PrincipalId("c1"),
                        toolName = "schema_reverse_start",
                        correlationKind = ApprovalCorrelationKind.IDEMPOTENCY_KEY,
                        correlationKey = "key-1",
                        payloadFingerprint = "fp",
                    )
                    wiring.aiWiring.operationalWiring.policyService.decide(attempt) shouldBe PolicyDecision.Allowed
                }
            } finally {
                owner.cleanupIfOwned()
                Files.deleteIfExists(policyFile)
                runCatching {
                    Files.walk(stateDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach { runCatching { Files.deleteIfExists(it) } }
                }
            }
        }

        test("--approval-grants-file without --server-state prints the divergence warning (AE-5)") {
            val stateDir = Files.createTempDirectory("dmigrate-build-grants-warn-")
            val grantsFile = Files.createTempFile("dmigrate-grants-warn-", ".yaml")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val (lines, sink) = stderrCapture()
            try {
                newWiring(approvalGrantsFile = grantsFile, stderr = sink)
                    .build(McpServerConfig(), owner, cursorKeyring = null).use { }
                lines.joinToString("\n") shouldContain "grants are durable, but the pending approval requests"
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

        test("no --approval-grants-file prints no divergence warning") {
            val stateDir = Files.createTempDirectory("dmigrate-build-grants-nowarn-")
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val (lines, sink) = stderrCapture()
            try {
                newWiring(stderr = sink).build(McpServerConfig(), owner, cursorKeyring = null).use { }
                lines.none { it.contains("pending approval requests") } shouldBe true
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

        test("--approval-grants-file with --server-state configured prints no divergence warning (AE-5)") {
            val stateDir = Files.createTempDirectory("dmigrate-build-jdbc-grants-")
            val configFile = Files.createTempFile("dmigrate-build-jdbc-grants-cfg-", ".yaml")
            val grantsFile = Files.createTempFile("dmigrate-build-jdbc-grants-", ".yaml")
            Files.writeString(
                configFile,
                """
                server:
                  state:
                    jdbcUrl: jdbc:postgresql://localhost/dmigrate-test
                """.trimIndent(),
            )
            val owner = StateDirOwner.of(StateDirResolver.resolve(cliOption = stateDir))
            val fakeFactory = ServerStateFactory { _, phaseC ->
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
                    cleanup = AutoCloseable { },
                )
            }
            val (lines, sink) = stderrCapture()
            try {
                newWiring(
                    connectionConfigPath = configFile,
                    approvalGrantsFile = grantsFile,
                    stderr = sink,
                    serverStateFactory = fakeFactory,
                ).build(McpServerConfig(), owner, cursorKeyring = null).use { }
                lines.none { it.contains("pending approval requests") } shouldBe true
            } finally {
                owner.cleanupIfOwned()
                Files.deleteIfExists(configFile)
                Files.deleteIfExists(grantsFile)
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
