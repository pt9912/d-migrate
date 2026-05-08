package dev.dmigrate.server.ports

import dev.dmigrate.server.core.connection.ConnectionReference

/**
 * LF-012 / LN-038: adapter-neutral port that loads
 * **secret-free** [ConnectionReference] records from configuration
 * (project YAML, server config, or a future config provider). The
 * port deliberately surfaces *only* the discovery-safe metadata —
 * `connectionId`, `displayName`, `dialectId`, `sensitivity`,
 * `credentialRef`, `providerRef` — and never the resolved JDBC URL,
 * password, or token.
 *
 * LF-012 / LN-038 forbids the discovery surfaces (`resources/list`,
 * `resources/read`, `*_list` tools) from materialising secrets.
 * Implementations satisfy that contract by parsing
 * `${ENV_VAR}`-shaped placeholders into `credentialRef` (e.g.
 * `"env:PG_PASS"`) without ever calling `System.getenv` — secret
 * expansion is delegated to [ConnectionSecretResolver] which only
 * the authorised runner / driver path may invoke.
 *
 * LF-012 / LN-038 / pre-D YAML (a connection entry that is a bare URL
 * string) is **not** a LF-012 / LN-038 discovery record. The loader
 * returns `null` (or skips the entry) for the bare-string form so
 * LF-012 / LN-038 CLI flows keep working without leaking the URL into
 * the LF-012 / LN-038 `ConnectionReferenceStore`.
 */
interface ConnectionReferenceConfigLoader {

    /**
     * Loads every LF-012 / LN-038-eligible connection reference from the
     * underlying configuration source. Returns an empty list when
     * the source is missing, contains no connections, or has only
     * bare-URL LF-012 / LN-038 entries.
     *
     * Implementations MUST NOT throw on a missing config file —
     * LF-012 / LN-038 bootstrap should still succeed for deployments that
     * run without a project YAML. Only malformed parse-able input
     * (top-level type mismatch, unknown sensitivity enum, etc.)
     * surfaces as [ConnectionReferenceConfigException].
     */
    fun loadAll(): List<ConnectionReference>
}

/**
 * Thrown when the underlying configuration source parses but
 * carries an unparseable LF-012 / LN-038 connection record (e.g. an unknown
 * `sensitivity` enum value, a non-string `displayName`). Surfaces
 * fail-closed during bootstrap so misconfigured deployments fail
 * loudly rather than silently dropping the connection.
 */
open class ConnectionReferenceConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
