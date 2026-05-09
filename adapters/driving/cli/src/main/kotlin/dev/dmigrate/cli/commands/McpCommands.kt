package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.core.util.toHex
import dev.dmigrate.mcp.registry.FileBackedApprovalGrantStore
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.security.SecureRandom
import java.time.Instant

/**
 * MCP-server entry point per LF-012 / LN-027 / LN-028 / LN-038
 * LF-012 / LN-027 / LN-028 / LN-038. The `serve` subcommand
 * activates the full LF-012 / LN-038 dispatch chain: every tool from the
 * LF-012 / LN-038 plan (`schema_validate`, `schema_generate`, `schema_compare`,
 * `artifact_upload*`, `artifact_chunk_get`, `job_status_get`,
 * plus `capabilities_list`) routes to its real handler, and every
 * `tools/call` records one structured audit event.
 *
 * §6.21: byte content (upload segments, artefact bodies) is file-backed
 * under the resolved state dir (`--mcp-state-dir`). LF-012 / LN-011 / LN-017 / LN-027 can also
 * persist the LF-012 / LN-011 / LN-017 / LN-027 server-state stores when `server.state.jdbcUrl`
 * or `D_MIGRATE_SERVER_STATE_JDBC_URL` is configured.
 */
class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "MCP-server commands (LF-012 / LN-038: stdio + Streamable HTTP)"

    init {
        subcommands(McpServeCommand(), McpCursorKeyCommand(), McpApprovalGrantCommand())
    }

    override fun run() = Unit
}

/**
 * Starts the MCP server in stdio or Streamable-HTTP mode. Wraps
 * [McpServeRunner] which holds the framework-independent orchestration.
 *
 * stdio: blocks until stdin closes (or SIGINT).
 * HTTP: blocks until SIGINT.
 *
 * §6.21 lifecycle: state dir is resolved + validated + locked before
 * any transport starts; CLI-owned tempdirs are deleted best-effort on
 * normal stop, SIGINT, and the start-error path.
 */
class McpServeCommand : CliktCommand(name = "serve") {
    override fun help(context: Context) =
        "Start the MCP server with the LF-012 / LN-038 dispatch chain. " +
            "Byte content is file-backed under --mcp-state-dir " +
            "(LF-012 / LN-011 / LN-017 / LN-027 server-state can be JDBC-backed via server.state)."

    private val transport by option(
        "--transport",
        help = "Transport: stdio (one process per client) or http (Streamable HTTP).",
    ).choice("stdio", "http").default("stdio")

    private val bind by option(
        "--bind",
        help = "HTTP bind address (default 127.0.0.1). Non-loopback requires --auth-mode != disabled.",
    ).default("127.0.0.1")

    private val port by option(
        "--port",
        help = "HTTP port (0 picks an ephemeral port).",
    ).int().default(0)

    private val publicBaseUrl by option(
        "--public-base-url",
        help = "Public base URL for HTTP. MUST be https. Required for non-loopback prod deployments.",
    )

    private val authMode by option(
        "--auth-mode",
        help = "HTTP auth mode (stdio ignores this). disabled is loopback-only.",
    ).choice("disabled", "jwt-jwks", "jwt-introspection").default("jwt-jwks")

    private val issuer by option(
        "--issuer",
        help = "OIDC issuer URI (required for jwt-jwks and jwt-introspection).",
    )

    private val jwksUrl by option(
        "--jwks-url",
        help = "JWKS URL (required for jwt-jwks).",
    )

    private val introspectionUrl by option(
        "--introspection-url",
        help = "RFC 7662 introspection endpoint (required for jwt-introspection).",
    )

    private val audience by option(
        "--audience",
        help = "Expected `aud` claim / OAuth Resource Indicator (required for jwt-* modes).",
    )

    private val stdioTokenFile by option(
        "--stdio-token-file",
        help = "JSON or YAML token-registry for the stdio transport (§12.10).",
    ).path()

    private val allowOrigin by option(
        "--allow-origin",
        help = "Origin allow-list entry (repeatable). Default loopback origins apply when the bind is loopback.",
    ).multiple()

    private val mcpStateDir by option(
        "--mcp-state-dir",
        help = "State dir for file-backed upload segments and artefact content. " +
            "Wins over \$DMIGRATE_MCP_STATE_DIR. Falls back to a CLI-owned tempdir " +
            "(deleted on stop). Operator-supplied dirs are single-writer (advisory " +
            ".lock) and survive the process; metadata stays in-process either way.",
    ).path()

    private val mcpStateOrphanRetention by option(
        "--mcp-state-orphan-retention",
        help = "Retention for orphaned byte files at startup. Wins over " +
            "\$DMIGRATE_MCP_STATE_ORPHAN_RETENTION; default 24h. Accepts " +
            "`never` (skip sweep — forensic mode), `0`/`0s` (delete every " +
            "store file at boot), <number><ms|s|m|h|d>, or ISO-8601 PT… . " +
            "Upload segments without surviving session metadata are always " +
            "swept under any non-`never` policy because they are " +
            "unreferenceable after restart.",
    )

    private val connectionConfigPath by option(
        "--connection-config",
        help = "Project/server YAML for LF-012 / LN-038 secret-free connection references. " +
            "Defaults to the root --config path when set.",
    ).path()

    private val cursorKeyringFile by option(
        "--cursor-keyring-file",
        help = "YAML keyring for HMAC-sealed MCP cursors. Required for deterministic multi-instance deployments.",
    ).path()

    private val approvalGrantsFile by option(
        "--approval-grants-file",
        help = "JSON/YAML ApprovalGrant store. Use with 'd-migrate mcp approval-grant issue' to approve pending jobs.",
    ).path()

    private val operationTimeoutSeconds by option(
        "--operation-timeout-seconds",
        help = "Timeout in seconds for upload finalisation leases and the stale-finalisation sweeper.",
    ).long().default(McpServerConfig.DEFAULT_OPERATION_TIMEOUT.toSeconds())

    override fun run() {
        val resolvedConfigPath = connectionConfigPath
            ?: (currentContext.parent?.parent?.command as? DMigrate)?.config
        val options = McpServeOptions(
            transport = transport,
            bind = bind,
            port = port,
            publicBaseUrl = publicBaseUrl,
            authMode = authMode,
            issuer = issuer,
            jwksUrl = jwksUrl,
            introspectionUrl = introspectionUrl,
            audience = audience,
            stdioTokenFile = stdioTokenFile,
            allowOrigin = allowOrigin,
            mcpStateDir = mcpStateDir,
            mcpStateOrphanRetention = mcpStateOrphanRetention,
            cursorKeyringFile = cursorKeyringFile,
            approvalGrantsFile = approvalGrantsFile,
            operationTimeoutSeconds = operationTimeoutSeconds,
        )
        val runner = McpServeRunner(
            options = options,
            stderr = { msg -> echo(msg, err = true) },
            effectiveConnectionConfigPath = resolvedConfigPath,
        )
        val exit = runner.execute()
        if (exit != 0) throw ProgramResult(exit)
    }
}

class McpApprovalGrantCommand : CliktCommand(name = "approval-grant") {
    override fun help(context: Context) = "Approval grant administration"

    init {
        subcommands(McpApprovalGrantIssueCommand())
    }

    override fun run() = Unit
}

class McpApprovalGrantIssueCommand : CliktCommand(name = "issue") {
    override fun help(context: Context) =
        "Issue a token-bound approval grant for a pending POLICY_REQUIRED challenge."

    private val file by option(
        "--file",
        help = "JSON/YAML ApprovalGrant store used by 'mcp serve --approval-grants-file'.",
    ).path().required()

    private val tenant by option("--tenant", help = "Tenant id from the pending challenge.").required()

    private val caller by option("--caller", help = "Principal id that started the pending job.").required()

    private val tool by option("--tool", help = "Tool name, e.g. schema_reverse_start.").required()

    private val approvalRequestId by option(
        "--approval-request-id",
        help = "approvalRequestId returned by POLICY_REQUIRED.",
    ).required()

    private val idempotencyKey by option(
        "--idempotency-key",
        help = "idempotencyKey used for the pending start call.",
    )

    private val approvalKey by option(
        "--approval-key",
        help = "approvalKey used for a pending synchronous policy-required call.",
    )

    private val payloadFingerprint by option(
        "--payload-fingerprint",
        help = "payloadFingerprint returned by POLICY_REQUIRED.",
    ).required()

    private val scope by option(
        "--scope",
        help = "Approved scope. Repeat for every required scope.",
    ).multiple(required = true)

    private val issuerFingerprint by option(
        "--issuer-fingerprint",
        help = "Stable issuer identity stored in the grant.",
    ).default("cli-approval-grant")

    private val grantSource by option(
        "--grant-source",
        help = "Audit/source label stored in the grant.",
    ).default("cli-admin")

    private val expiresAt by option(
        "--expires-at",
        help = "RFC-3339 expiry instant. Overrides --ttl-seconds.",
    )

    private val ttlSeconds by option(
        "--ttl-seconds",
        help = "Grant lifetime when --expires-at is omitted.",
    ).long().default(300)

    private val token by option(
        "--token",
        help = "Raw token to issue. Defaults to a generated token; only its fingerprint is stored.",
    )

    override fun run() {
        val rawToken = token ?: generatedToken()
        val expiry = expiresAt?.let(Instant::parse) ?: Instant.now().plusSeconds(ttlSeconds)
        val correlation = resolveCorrelation()
        val grant = ApprovalGrant(
            approvalRequestId = approvalRequestId,
            correlationKind = correlation.kind,
            correlationKey = correlation.key,
            approvalTokenFingerprint = ApprovalTokenFingerprint.compute(rawToken),
            toolName = tool,
            tenantId = TenantId(tenant),
            callerId = PrincipalId(caller),
            payloadFingerprint = payloadFingerprint,
            issuerFingerprint = issuerFingerprint,
            issuedScopes = scope.toSet(),
            grantSource = grantSource,
            expiresAt = expiry,
        )
        FileBackedApprovalGrantStore(file).save(grant)
        echo("approvalToken=$rawToken")
        echo("expiresAt=${grant.expiresAt}")
    }

    private fun resolveCorrelation(): GrantCorrelation {
        val idempotency = idempotencyKey
        val approval = approvalKey
        return when {
            idempotency != null && approval != null ->
                throw UsageError("Use exactly one of --idempotency-key or --approval-key")
            idempotency != null -> GrantCorrelation(ApprovalCorrelationKind.IDEMPOTENCY_KEY, idempotency)
            approval != null -> GrantCorrelation(ApprovalCorrelationKind.APPROVAL_KEY, approval)
            else -> throw UsageError("One of --idempotency-key or --approval-key is required")
        }
    }

    private fun generatedToken(): String {
        val bytes = ByteArray(32)
        SECURE_RANDOM.nextBytes(bytes)
        return "appr_" + bytes.toHex()
    }

    private companion object {
        val SECURE_RANDOM: SecureRandom = SecureRandom()
    }
}

private data class GrantCorrelation(
    val kind: ApprovalCorrelationKind,
    val key: String,
)

class McpCursorKeyCommand : CliktCommand(name = "cursor-key") {
    override fun help(context: Context) = "Generate and validate MCP cursor keyring files"

    init {
        subcommands(McpCursorKeyGenerateCommand(), McpCursorKeyValidateCommand())
    }

    override fun run() = Unit
}

class McpCursorKeyGenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Generate a YAML cursor keyring with one active signing key"

    private val kid by option(
        "--kid",
        help = "Stable key id to place into future cursor envelopes.",
    ).required()

    override fun run() {
        echo(McpCursorKeyringConfig.renderSingleKeyFile(kid))
    }
}

class McpCursorKeyValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a cursor keyring YAML file"

    private val keyringFile by option(
        "--cursor-keyring-file",
        help = "YAML keyring to validate.",
    ).path().required()

    override fun run() {
        try {
            McpCursorKeyringConfig.load(keyringFile)
            echo("cursor keyring valid: $keyringFile")
        } catch (failure: McpCursorKeyringConfigError) {
            echo("cursor keyring invalid: ${failure.message}", err = true)
            throw ProgramResult(2)
        }
    }
}
