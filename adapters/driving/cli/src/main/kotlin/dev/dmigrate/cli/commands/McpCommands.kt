package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerBootstrap
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.server.McpStartOutcome
import dev.dmigrate.mcp.server.validate
import dev.dmigrate.mcp.server.validateForStdio
import java.net.URI

/**
 * MCP-server entry point per `ImpPlan-0.9.6-B.md` §6.11. Phase B
 * exposes the `serve` subcommand only — `tools/list`, `tools/call`,
 * `resources/list` and `resources/templates/list` work, but only
 * `capabilities_list` has a Phase-B handler. Production use needs
 * Phase C/D handlers wired into the bootstrap.
 */
class McpCommand : CliktCommand(name = "mcp") {
    override fun help(context: Context) = "MCP-server commands (Phase B: stdio + Streamable HTTP)"

    init {
        subcommands(McpServeCommand())
    }

    override fun run() = Unit
}

/**
 * Starts the MCP server in stdio or Streamable-HTTP mode. Wraps
 * [McpServerBootstrap]. Validation per §12.12 happens inside the
 * bootstrap; configuration errors come back as exit code 2 with one
 * line per violation.
 *
 * stdio: blocks until stdin closes (or SIGINT).
 * HTTP: blocks until SIGINT.
 */
class McpServeCommand : CliktCommand(name = "serve") {
    override fun help(context: Context) =
        "Start the MCP server. Phase B: only `capabilities_list` is implemented."

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

    override fun run() {
        val config = buildConfig()
        // §12.15: stdio ignores authMode entirely. Use the slimmer
        // validation so a default-config (authMode=JWT_JWKS, no
        // issuer) still starts the stdio server. Both startStdio and
        // startHttp re-validate at the bootstrap layer with the
        // matching helper, but the CLI surface produces clearer
        // error messages when it catches violations early.
        val errors = when (transport) {
            "stdio" -> config.validateForStdio()
            "http" -> config.validate()
            else -> error("transport check failed: $transport")
        }
        if (errors.isNotEmpty()) {
            echo("MCP server configuration is invalid:", err = true)
            errors.forEach { echo("  - $it", err = true) }
            throw ProgramResult(2)
        }
        when (transport) {
            "stdio" -> startStdio(config)
            "http" -> startHttp(config)
            else -> error("transport check failed: $transport")
        }
    }

    private fun buildConfig(): McpServerConfig {
        val origins = if (allowOrigin.isEmpty()) {
            McpServerConfig.DEFAULT_LOOPBACK_ORIGINS
        } else {
            allowOrigin.toSet()
        }
        return McpServerConfig(
            bindAddress = bind,
            port = port,
            publicBaseUrl = publicBaseUrl?.let(URI::create),
            allowedOrigins = origins,
            authMode = when (authMode) {
                "disabled" -> AuthMode.DISABLED
                "jwt-jwks" -> AuthMode.JWT_JWKS
                "jwt-introspection" -> AuthMode.JWT_INTROSPECTION
                else -> error("auth-mode check failed: $authMode")
            },
            issuer = issuer?.let(URI::create),
            jwksUrl = jwksUrl?.let(URI::create),
            introspectionUrl = introspectionUrl?.let(URI::create),
            audience = audience,
            stdioTokenFile = stdioTokenFile,
        )
    }

    private fun startStdio(config: McpServerConfig) {
        when (val outcome = McpServerBootstrap.startStdio(config = config)) {
            is McpStartOutcome.ConfigError -> reportConfigErrors(outcome.errors)
            is McpStartOutcome.Started -> {
                echo("MCP stdio server started; reading from stdin until EOF/SIGINT.", err = true)
                Runtime.getRuntime().addShutdownHook(Thread(outcome.handle::stop))
                // The stdio reader runs on a daemon thread; main blocks
                // here so the JVM stays up until stdin closes.
                Thread.currentThread().join()
            }
        }
    }

    private fun startHttp(config: McpServerConfig) {
        when (val outcome = McpServerBootstrap.startHttp(config = config)) {
            is McpStartOutcome.ConfigError -> reportConfigErrors(outcome.errors)
            is McpStartOutcome.Started -> {
                echo("MCP HTTP server listening on $bind:${outcome.handle.boundPort}", err = true)
                Runtime.getRuntime().addShutdownHook(Thread(outcome.handle::stop))
                Thread.currentThread().join()
            }
        }
    }

    private fun reportConfigErrors(errors: List<String>): Nothing {
        echo("MCP server configuration is invalid:", err = true)
        errors.forEach { echo("  - $it", err = true) }
        throw ProgramResult(2)
    }
}
