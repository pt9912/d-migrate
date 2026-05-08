package dev.dmigrate.mcp.auth

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.StdioTokenGrant
import dev.dmigrate.server.ports.StdioTokenStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * File-backed [StdioTokenStore] per `ImpPlan-0.9.6-B.md` §12.10.
 *
 * Akzeptiert `.json`, `.yaml` und `.yml`. Das Datenformat ist gleich
 * (Jackson-tree); Format-Erkennung geht ueber die Datei-Endung. Beim
 * Konstruieren wird die Datei einmal eingelesen und in eine immutable
 * Map projiziert. Ueberschreiben zur Laufzeit ist nicht vorgesehen —
 * Token-Rotation erfordert einen Server-Neustart (Phase B Scope).
 *
 * Erwartetes Wurzelobjekt:
 * ```yaml
 * tokens:
 *   - fingerprint: "<sha256-hex>"
 *     principalId: "alice"
 *     tenantId: "acme"
 *     scopes: ["dmigrate:read"]
 *     isAdmin: false
 *     auditSubject: "alice@acme"
 *     expiresAt: "2026-12-31T00:00:00Z"
 * ```
 *
 * Konstruktor wirft [StdioTokenStoreLoadException] wenn die Datei nicht
 * lesbar ist, das Format-Schema verletzt oder ein `expiresAt` nicht
 * RFC-3339-parsebar ist. Die Validierung in
 * `McpServerConfig.validate()` (§12.12) prueft `Files.isReadable` vor
 * Aufruf, der hier geworfene Fehler ist also seltener Folge eines
 * gleichzeitigen FS-Wechsels oder eines kaputten File-Inhalts.
 */
internal class FileStdioTokenStore(
    grants: Map<String, StdioTokenGrant>,
) : StdioTokenStore {

    private val grants: Map<String, StdioTokenGrant> = grants.toMap()

    override fun lookup(tokenFingerprint: String): StdioTokenGrant? =
        grants[tokenFingerprint]

    companion object {

        /**
         * Liest die Datei unter [path] und baut einen
         * [FileStdioTokenStore]. Die Datei-Endung waehlt den Parser;
         * bei `.yaml`/`.yml` wird Jacksons YAMLFactory benutzt, sonst
         * der JSON-Default.
         */
        fun load(path: Path): FileStdioTokenStore {
            val text = try {
                Files.readString(path)
            } catch (e: java.io.IOException) {
                throw StdioTokenStoreLoadException(
                    "stdio token file unreadable (path='$path'): ${e.message}",
                    e,
                )
            }
            val mapper = mapperFor(path)
            val root = try {
                mapper.readTree(text)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw StdioTokenStoreLoadException(
                    "stdio token file malformed (path='$path'): ${e.originalMessage ?: e.message}",
                    e,
                )
            }
            return FileStdioTokenStore(parseGrants(root, path))
        }

        private fun mapperFor(path: Path): ObjectMapper {
            val name = path.fileName?.toString()?.lowercase() ?: ""
            val isYaml = name.endsWith(".yaml") || name.endsWith(".yml")
            return if (isYaml) ObjectMapper(YAMLFactory()) else ObjectMapper()
        }

        private fun parseGrants(root: JsonNode, path: Path): Map<String, StdioTokenGrant> {
            if (!root.isObject) {
                throw StdioTokenStoreLoadException(
                    "stdio token file root must be an object (path='$path')",
                )
            }
            val tokens = root.get("tokens")
                ?: throw StdioTokenStoreLoadException(
                    "stdio token file missing 'tokens' array (path='$path')",
                )
            if (!tokens.isArray) {
                throw StdioTokenStoreLoadException(
                    "stdio token file 'tokens' must be an array (path='$path')",
                )
            }
            val out = LinkedHashMap<String, StdioTokenGrant>()
            tokens.forEachIndexed { idx, node -> out += parseGrantEntry(node, idx, path) }
            return out
        }

        private fun parseGrantEntry(
            node: JsonNode,
            index: Int,
            path: Path,
        ): Pair<String, StdioTokenGrant> {
            if (!node.isObject) {
                throw StdioTokenStoreLoadException(
                    "stdio token entry #$index is not an object (path='$path')",
                )
            }
            val fingerprint = requireText(node, "fingerprint", index, path)
            val grant = StdioTokenGrant(
                principalId = PrincipalId(requireText(node, "principalId", index, path)),
                tenantId = TenantId(requireText(node, "tenantId", index, path)),
                scopes = requireScopes(node, index, path),
                isAdmin = node.get("isAdmin")?.asBoolean(false) ?: false,
                auditSubject = requireText(node, "auditSubject", index, path),
                expiresAt = parseExpiresAt(node, index, path),
            )
            return fingerprint to grant
        }

        private fun requireText(node: JsonNode, field: String, index: Int, path: Path): String {
            val raw = node.get(field)
            if (raw == null || !raw.isTextual || raw.asText().isBlank()) {
                throw StdioTokenStoreLoadException(
                    "stdio token entry #$index missing/blank '$field' (path='$path')",
                )
            }
            return raw.asText()
        }

        private fun requireScopes(node: JsonNode, index: Int, path: Path): Set<String> {
            val scopes = node.get("scopes")
                ?: throw StdioTokenStoreLoadException(
                    "stdio token entry #$index missing 'scopes' (path='$path')",
                )
            if (!scopes.isArray) {
                throw StdioTokenStoreLoadException(
                    "stdio token entry #$index 'scopes' must be an array (path='$path')",
                )
            }
            return scopes.mapNotNull { it.asText().takeUnless { s -> s.isBlank() } }.toSet()
        }

        private fun parseExpiresAt(node: JsonNode, index: Int, path: Path): Instant {
            val raw = requireText(node, "expiresAt", index, path)
            return try {
                Instant.parse(raw)
            } catch (e: DateTimeParseException) {
                throw StdioTokenStoreLoadException(
                    "stdio token entry #$index 'expiresAt' is not RFC-3339 (got '$raw', path='$path')",
                    e,
                )
            }
        }
    }
}

/** Wrappt jeden Fehler beim Laden von [FileStdioTokenStore.load]. */
internal class StdioTokenStoreLoadException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
