package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ConnectionReferenceConfigException
import dev.dmigrate.server.ports.ConnectionReferenceConfigLoader
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan-D §8 + §10.10 YAML-backed implementation of
 * [ConnectionReferenceConfigLoader]. Reads a project / server YAML
 * file shaped like:
 *
 * ```yaml
 * database:
 *   connections:
 *     pg-prod:
 *       displayName: "Production DB"
 *       dialectId: postgresql
 *       sensitivity: PRODUCTION
 *       credentialRef: env:PG_PASS
 *       providerRef: secrets-manager
 *       allowedPrincipalIds:
 *         - alice
 *         - bob
 *       allowedScopes:
 *         - dmigrate:data:write
 * ```
 *
 * **Phase-C compatibility**: a string-form entry
 * (`pg-prod: jdbc:postgresql://...`) is the legacy CLI shape.
 * Plan-D §3.7 forbids materialising bare URLs into the discovery
 * surface, so the loader silently drops bare-string entries — they
 * stay invisible to `resources/list`, `resources/read`, and the
 * `*_list` discovery tools. CLI flows that need to resolve a bare
 * URL keep using `NamedConnectionResolver` directly.
 *
 * **Secrets**: `credentialRef` is captured verbatim (e.g.
 * `"env:PG_PASS"`) and never expanded here. Expansion lives in
 * [dev.dmigrate.server.ports.ConnectionSecretResolver] which only
 * the authorised runner / driver path may invoke.
 *
 * **Tenant scoping**: every loaded reference is bound to
 * [defaultTenantId]. Multi-tenant deployments wire one loader per
 * tenant, or extend the YAML schema with a `tenantId` field — the
 * loader stays single-tenant on purpose so the bootstrap can
 * compose multiple loaders safely.
 *
 * **Missing file**: returns an empty list (Plan-D §10.10: "MCP-
 * Bootstrap laedt nur secret-freie ConnectionReference-Records" —
 * a missing config is a "no records" outcome, not a hard failure).
 */
class YamlConnectionReferenceLoader(
    private val configPath: Path,
    private val defaultTenantId: TenantId,
) : ConnectionReferenceConfigLoader {

    override fun loadAll(): List<ConnectionReference> {
        if (!Files.isRegularFile(configPath)) return emptyList()
        val parsed = parseYaml(configPath)
        val database = parsed["database"] as? Map<*, *> ?: return emptyList()
        val connectionsRaw = database["connections"] as? Map<*, *> ?: return emptyList()
        return connectionsRaw.entries.mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            // String-form is the Phase-C compat shape — drop it
            // from the Phase-D discovery universe.
            if (value is String) return@mapNotNull null
            val asMap = value as? Map<*, *> ?: throw ConnectionReferenceConfigException(
                "connection '$name' must be a string (Phase-C compat) or a mapping (Phase-D record), " +
                    "got ${value?.let { it::class.simpleName }}",
            )
            buildReference(name, asMap)
        }
    }

    private fun buildReference(connectionId: String, source: Map<*, *>): ConnectionReference {
        val displayName = requireString(source, connectionId, "displayName")
        val dialectId = requireString(source, connectionId, "dialectId")
        val sensitivityRaw = requireString(source, connectionId, "sensitivity")
        val sensitivity = parseSensitivity(connectionId, sensitivityRaw)
        return ConnectionReference(
            connectionId = connectionId,
            tenantId = defaultTenantId,
            displayName = displayName,
            dialectId = dialectId,
            sensitivity = sensitivity,
            resourceUri = ServerResourceUri(defaultTenantId, ResourceKind.CONNECTIONS, connectionId),
            credentialRef = optionalString(source, connectionId, "credentialRef"),
            providerRef = optionalString(source, connectionId, "providerRef"),
            allowedPrincipalIds = optionalStringList(source, connectionId, "allowedPrincipalIds")
                ?.map { PrincipalId(it) }
                ?.toSet(),
            allowedScopes = optionalStringList(source, connectionId, "allowedScopes")?.toSet(),
        )
    }

    private fun parseYaml(path: Path): Map<*, *> {
        val parsed = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(path).use { input ->
                Load(settings).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw ConnectionReferenceConfigException(
                "failed to parse connection config at $path: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
        return parsed as? Map<*, *> ?: throw ConnectionReferenceConfigException(
            "connection config at $path: top-level YAML must be a mapping",
        )
    }

    @Suppress("SwallowedException")
    private fun parseSensitivity(connectionId: String, raw: String): ConnectionSensitivity = try {
        ConnectionSensitivity.valueOf(raw)
    } catch (e: IllegalArgumentException) {
        // SwallowedException: the JVM "No enum constant …" message
        // would echo the raw enum class path back to the caller;
        // operators only need the offending value + allowed set.
        throw ConnectionReferenceConfigException(
            "connection '$connectionId': unknown sensitivity '$raw'; expected one of " +
                ConnectionSensitivity.entries.joinToString(",") { it.name },
        )
    }

    private fun requireString(source: Map<*, *>, connectionId: String, field: String): String =
        optionalString(source, connectionId, field)
            ?: throw ConnectionReferenceConfigException(
                "connection '$connectionId': missing required field '$field'",
            )

    private fun optionalString(source: Map<*, *>, connectionId: String, field: String): String? {
        val raw = source[field] ?: return null
        return raw as? String ?: throw ConnectionReferenceConfigException(
            "connection '$connectionId': field '$field' must be a string, got ${raw::class.simpleName}",
        )
    }

    private fun optionalStringList(
        source: Map<*, *>,
        connectionId: String,
        field: String,
    ): List<String>? {
        val raw = source[field] ?: return null
        val asList = raw as? List<*> ?: throw ConnectionReferenceConfigException(
            "connection '$connectionId': field '$field' must be a list, got ${raw::class.simpleName}",
        )
        return asList.map {
            it as? String ?: throw ConnectionReferenceConfigException(
                "connection '$connectionId': '$field' contains non-string entry ${it?.let { e -> e::class.simpleName }}",
            )
        }
    }
}
