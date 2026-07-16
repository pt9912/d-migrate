package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialProviderRegistry

/**
 * Zentrale Provider-Liste der O4-Naht (ADR 0035) — **eine** Quelle für MCP (`McpServeWiring`) und
 * CLI (`--source`/`--target`-Pfad), damit beide Welten exakt dieselben `credentialRef`-Schemes
 * unterstützen.
 *
 * Reihenfolge egal (Dispatch am Scheme-Prefix, kollisionsfrei). Aktuell: `env:` + `file:`.
 */
fun defaultCredentialProviderRegistry(): CredentialProviderRegistry =
    CredentialProviderRegistry(
        listOf(
            EnvCredentialProvider(),
            FileCredentialProvider(),
        ),
    )
