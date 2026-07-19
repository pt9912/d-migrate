package dev.dmigrate.connection

import dev.dmigrate.server.ports.CredentialProviderRegistry

/**
 * Zentrale Provider-Liste der O4-Naht (ADR 0035) — **eine** Quelle für MCP (`McpServeWiring`) und
 * CLI (`--source`/`--target`-Pfad), damit beide Welten exakt dieselben `credentialRef`-Schemes
 * unterstützen.
 *
 * Reihenfolge egal (Dispatch am Scheme-Prefix, kollisionsfrei). Aktuell: `env:` + `file:` +
 * `keychain:` (ADR 0040, Default-Backend native-freier Shell-out).
 *
 * Die Registry ist zustandslos/immutable (Provider halten nur Lambdas; `env:` liest die Umgebung
 * erst zur Auflösungszeit; der Keychain-Shell-out prüft OS/Tool erst zur Auflösungszeit) → **eine**
 * geteilte Instanz statt pro Aufrufer neu zu bauen (Review F3).
 */
private val sharedCredentialProviderRegistry: CredentialProviderRegistry =
    CredentialProviderRegistry(
        listOf(
            EnvCredentialProvider(),
            FileCredentialProvider(),
            KeychainCredentialProvider(ShelloutKeychainBackend()),
        ),
    )

fun defaultCredentialProviderRegistry(): CredentialProviderRegistry = sharedCredentialProviderRegistry
