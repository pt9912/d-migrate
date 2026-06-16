package dev.dmigrate.cli.integration

import dev.dmigrate.mcp.registry.AiMcpRegistries
import dev.dmigrate.mcp.registry.AiMcpWiring
import dev.dmigrate.mcp.registry.McpCoreJobWorkerFactory
import dev.dmigrate.mcp.registry.OperationalMcpWiring
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import java.nio.file.Path

/**
 * C-MCP Operational-Harness variant — bootstraps the MCP stdio
 * transport with the production-style component composition
 * `AiMcpRegistries.defaultComponents(AiMcpWiring(OperationalMcpWiring(...)))`
 * passed as the `components` override to
 * [dev.dmigrate.mcp.server.McpServerBootstrap.startStdio].
 *
 * Plan-Doc: `docs/planning/done-archive/quality-coverage-expansion-plan.md`
 * §5.3 (Sub-Slice C-MCP).
 *
 * Differences vs the runtime-only [StdioHarness] (LF-012-E1):
 * - [StdioHarness.tryStart] uses the bootstrap-default components
 *   computed via `McpRuntimeRegistries.defaultComponents`. The
 *   default path falls back to `PassthroughJobWorkerFactory` for
 *   `schema_reverse_start` / `data_profile_start` /
 *   `schema_compare_start` and never publishes artefacts.
 * - This harness wires [OperationalMcpWiring] with
 *   `fallbackJobWorkerFactory = McpCoreJobWorkerFactory(...)` so the
 *   controlled read-side jobs reach terminal status with a published
 *   artefact. The default `JobExecutorConfig.SYNC_DEFAULT` makes the
 *   worker run inline, so the tools/call response already reflects
 *   the terminal job status (no client-side polling needed).
 * - The [ConnectionSecretResolver] is a test seam — callers register
 *   specific [dev.dmigrate.server.core.connection.ConnectionReference]
 *   entries via the wiring's `connectionStore` and supply a resolver
 *   that materialises them onto real Testcontainers / file SQLite
 *   URLs.
 *
 * The CLI-seam [dev.dmigrate.cli.commands.McpServeWiring] is
 * deliberately bypassed — the plan-doc forbids piggy-backing on
 * `McpServeRunner` because it carries CLI-only state (StateDirOwner,
 * ApprovalGrantsFile, CursorKeyring) that should not leak into an
 * in-process scenario test. The real `mcp serve` CLI lifecycle is
 * covered separately by `McpRealCliSubprocessTest`.
 */
internal object OperationalHarness {

    /**
     * Starts an operational MCP stdio harness. Returns a regular
     * [StdioHarness] (the request/response machinery is identical;
     * only the components composition differs). Failure modes —
     * lock conflicts, bootstrap config errors — surface via
     * [StdioHarness.StartOutcome] exactly as `StdioHarness.tryStart`
     * does.
     */
    /**
     * Convenience helper for `ConfiguredPolicyService(rules = emptyList(),
     * defaultEffect = Allow)`. The success-path scenario passes this
     * so the operational tools are reachable without staging policy
     * rules. The blocker scenario leaves [policyService] at its
     * default (`Deny("policy:no-rule")` per [ConfiguredPolicyService])
     * and asserts the `POLICY_DENIED` envelope.
     */
    val ALLOW_ALL_POLICY: PolicyService = ConfiguredPolicyService(
        rules = emptyList(),
        defaultEffect = PolicyEffect.Allow,
    )

    fun start(
        stateDir: Path,
        principal: PrincipalContext,
        connectionSecretResolver: ConnectionSecretResolver,
        limits: McpLimitsConfig = McpLimitsConfig(),
        policyService: PolicyService = ConfiguredPolicyService(rules = emptyList()),
    ): StdioHarness = when (val outcome = tryStart(stateDir, principal, connectionSecretResolver, limits, policyService)) {
        is StdioHarness.StartOutcome.Started -> outcome.harness
        is StdioHarness.StartOutcome.LockConflict ->
            error("operational harness lock conflict: ${outcome.diagnostic}")
        is StdioHarness.StartOutcome.BootstrapError ->
            error("operational harness bootstrap failed: ${outcome.errors.joinToString()}")
        is StdioHarness.StartOutcome.LockFailed ->
            error("operational harness lock failed: ${outcome.message}")
    }

    fun tryStart(
        stateDir: Path,
        principal: PrincipalContext,
        connectionSecretResolver: ConnectionSecretResolver,
        limits: McpLimitsConfig = McpLimitsConfig(),
        policyService: PolicyService = ConfiguredPolicyService(rules = emptyList()),
    ): StdioHarness.StartOutcome {
        // Build the runtime wiring eagerly so the operational+AI
        // composition references the SAME stores the bootstrap will
        // see. Passing it back via `wiringBundleOverride` keeps both
        // sides aligned without duplicating the IntegrationFixtures
        // wiring.
        val wiringBundle = IntegrationFixtures.integrationWiring(stateDir, limits = limits)
        val runtimeWiring = wiringBundle.wiring

        // Same idempotencyStore reference is shared between
        // OperationalMcpWiring + InMemoryJobStartTransaction; passing
        // two different instances would leave JobStartService
        // observing "pending" because its lease check reads from a
        // store the transaction never committed to (review during
        // first C-MCP green run).
        val idempotencyStore = InMemoryIdempotencyStore()
        val operationalWiring = OperationalMcpWiring(
            runtimeWiring = runtimeWiring,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = InMemoryJobStartTransaction(
                jobStore = runtimeWiring.jobStore,
                idempotencyStore = idempotencyStore,
            ),
            workerHandleRegistry = InMemoryWorkerHandleRegistry(),
            approvalGrantStore = InMemoryApprovalGrantStore(),
            policyService = policyService,
            // Plan-doc C-MCP: McpCoreJobWorkerFactory binds
            // schema_reverse_start / data_profile_start /
            // schema_compare_start to their real runners.
            fallbackJobWorkerFactory = McpCoreJobWorkerFactory(
                connectionStore = runtimeWiring.connectionStore,
                connectionSecretResolver = connectionSecretResolver,
                artifactStore = runtimeWiring.artifactStore,
                artifactContentStore = runtimeWiring.artifactContentStore,
                schemaStore = runtimeWiring.schemaStore,
                profileStore = runtimeWiring.profileStore,
                diffStore = runtimeWiring.diffStore,
                limits = runtimeWiring.limits,
                clock = runtimeWiring.clock,
            ),
            connectionSecretResolver = connectionSecretResolver,
        )
        val aiWiring = AiMcpWiring(operationalWiring = operationalWiring)
        val components = AiMcpRegistries.defaultComponents(aiWiring)

        return StdioHarness.tryStart(
            stateDir = stateDir,
            principal = principal,
            limits = limits,
            wiringBundleOverride = wiringBundle,
            componentsOverride = components,
        )
    }
}
