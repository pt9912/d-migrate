package dev.dmigrate.server.ports

import dev.dmigrate.server.core.connection.ConnectionReference

/**
 * Plan-D §8.1 + §10.10: adapter-neutral port that loads
 * **secret-free** [ConnectionReference] records from configuration
 * (project YAML, server config, or a future config provider). The
 * port deliberately surfaces *only* the discovery-safe metadata —
 * `connectionId`, `displayName`, `dialectId`, `sensitivity`,
 * `credentialRef`, `providerRef` — and never the resolved JDBC URL,
 * password, or token.
 *
 * Plan-D §3.7 forbids the discovery surfaces (`resources/list`,
 * `resources/read`, `*_list` tools) from materialising secrets.
 * Implementations satisfy that contract by parsing
 * `${ENV_VAR}`-shaped placeholders into `credentialRef` (e.g.
 * `"env:PG_PASS"`) without ever calling `System.getenv` — secret
 * expansion is delegated to [ConnectionSecretResolver] which only
 * the authorised runner / driver path may invoke.
 *
 * Phase-C / pre-D YAML (a connection entry that is a bare URL
 * string) is **not** a Phase-D discovery record. The loader
 * returns `null` (or skips the entry) for the bare-string form so
 * Phase-B/-C CLI flows keep working without leaking the URL into
 * the Phase-D `ConnectionReferenceStore`.
 */
interface ConnectionReferenceConfigLoader {

    /**
     * Loads every Phase-D-eligible connection reference from the
     * underlying configuration source. Returns an empty list when
     * the source is missing, contains no connections, or has only
     * bare-URL Phase-C entries.
     *
     * Implementations MUST NOT throw on a missing config file —
     * Phase-D bootstrap should still succeed for deployments that
     * run without a project YAML. Only malformed parse-able input
     * (top-level type mismatch, unknown sensitivity enum, etc.)
     * surfaces as [ConnectionReferenceConfigException].
     */
    fun loadAll(): List<ConnectionReference>
}

/**
 * Thrown when the underlying configuration source parses but
 * carries an unparseable Phase-D connection record (e.g. an unknown
 * `sensitivity` enum value, a non-string `displayName`). Surfaces
 * fail-closed during bootstrap so misconfigured deployments fail
 * loudly rather than silently dropping the connection.
 */
open class ConnectionReferenceConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
