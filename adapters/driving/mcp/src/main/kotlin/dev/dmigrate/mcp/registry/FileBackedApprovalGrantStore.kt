package dev.dmigrate.mcp.registry

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.approval.ApprovalGrant
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ApprovalGrantStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant

/**
 * File-backed ApprovalGrantStore for single-node MCP deployments.
 *
 * The server reloads the file for every lookup, so an operator can issue
 * a grant through the CLI while `mcp serve` is running. Raw approval tokens
 * are never persisted; the file contains only token fingerprints.
 */
class FileBackedApprovalGrantStore(
    private val path: Path,
) : ApprovalGrantStore {

    private val mapper = mapperFor(path)

    @Synchronized
    override fun save(grant: ApprovalGrant): ApprovalGrant {
        val next = loadGrants()
            .filterNot {
                it.tenantId == grant.tenantId &&
                    it.approvalTokenFingerprint == grant.approvalTokenFingerprint
            } + grant
        writeGrants(next)
        return grant
    }

    @Synchronized
    override fun findByTokenFingerprint(
        tenantId: TenantId,
        approvalTokenFingerprint: String,
    ): ApprovalGrant? =
        loadGrants().firstOrNull {
            it.tenantId == tenantId &&
                it.approvalTokenFingerprint == approvalTokenFingerprint
        }

    @Synchronized
    override fun deleteExpired(now: Instant): Int {
        val current = loadGrants()
        val retained = current.filter { it.expiresAt.isAfter(now) }
        if (retained.size != current.size) {
            writeGrants(retained)
        }
        return current.size - retained.size
    }

    private fun loadGrants(): List<ApprovalGrant> {
        if (!Files.exists(path)) return emptyList()
        if (!Files.isReadable(path)) {
            error(
                "approval grants file is not readable: $path " +
                    "(check ownership/permissions -- the file may have been rewritten by a " +
                    "different OS user, e.g. the CLI issuing a grant as a different UID than " +
                    "the running server process)",
            )
        }
        val root = mapper.readTree(path.toFile()) ?: return emptyList()
        val grants = root.get("grants") ?: return emptyList()
        require(grants.isArray) { "approval grants file must contain an array field 'grants'" }
        return grants.map(::toGrant)
    }

    private fun writeGrants(grants: List<ApprovalGrant>) {
        path.parent?.let(Files::createDirectories)
        val root = mapper.createObjectNode()
        val array = mapper.createArrayNode()
        grants.sortedWith(compareBy({ it.tenantId.value }, { it.approvalTokenFingerprint }))
            .map(::toNode)
            .forEach(array::add)
        root.set<ObjectNode>("grants", array)

        val tmp = Files.createTempFile(path.parent ?: Path.of("."), path.fileName.toString(), ".tmp")
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root)
            widenReadPermissions(tmp)
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    /**
     * `Files.createTempFile` defaults to owner-only (`rw-------`) on
     * POSIX filesystems, and `ATOMIC_MOVE` carries that onto the
     * replaced target regardless of its previous permissions. The CLI
     * (`mcp approval-grant issue`) and the running `mcp serve` process
     * commonly run as different OS users (containerised server, host-
     * invoked CLI, or vice versa) -- an owner-only grants file becomes
     * unreadable to the server the moment an operator issues the next
     * grant, surfacing as an unhandled `IllegalStateException` deep in
     * the approval-validation path (found live: server UID 10001 could
     * not read a file just written by CLI UID 1000). The file holds
     * only token fingerprints, never raw secrets (see class KDoc), so
     * widening read access is safe.
     */
    private fun widenReadPermissions(file: Path) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) -- nothing to adjust.
        }
    }

    private fun toGrant(node: JsonNode): ApprovalGrant =
        ApprovalGrant(
            approvalRequestId = node.requiredText("approvalRequestId"),
            correlationKind = ApprovalCorrelationKind.valueOf(node.requiredText("correlationKind")),
            correlationKey = node.requiredText("correlationKey"),
            approvalTokenFingerprint = node.requiredText("approvalTokenFingerprint"),
            toolName = node.requiredText("toolName"),
            tenantId = TenantId(node.requiredText("tenantId")),
            callerId = PrincipalId(node.requiredText("callerId")),
            payloadFingerprint = node.requiredText("payloadFingerprint"),
            issuerFingerprint = node.requiredText("issuerFingerprint"),
            issuedScopes = node.withArray<JsonNode>("issuedScopes").map { it.asText() }.toSet(),
            grantSource = node.requiredText("grantSource"),
            expiresAt = Instant.parse(node.requiredText("expiresAt")),
        )

    private fun toNode(grant: ApprovalGrant): ObjectNode =
        mapper.createObjectNode().apply {
            put("approvalRequestId", grant.approvalRequestId)
            put("correlationKind", grant.correlationKind.name)
            put("correlationKey", grant.correlationKey)
            put("approvalTokenFingerprint", grant.approvalTokenFingerprint)
            put("toolName", grant.toolName)
            put("tenantId", grant.tenantId.value)
            put("callerId", grant.callerId.value)
            put("payloadFingerprint", grant.payloadFingerprint)
            put("issuerFingerprint", grant.issuerFingerprint)
            set<ObjectNode>(
                "issuedScopes",
                mapper.createArrayNode().also { scopes ->
                    grant.issuedScopes.sorted().forEach(scopes::add)
                },
            )
            put("grantSource", grant.grantSource)
            put("expiresAt", grant.expiresAt.toString())
        }

    private fun JsonNode.requiredText(field: String): String {
        val node = get(field) ?: error("approval grant entry missing '$field'")
        require(node.isTextual) { "approval grant field '$field' must be a string" }
        return node.asText()
    }

    private companion object {
        fun mapperFor(path: Path): ObjectMapper {
            val lower = path.fileName.toString().lowercase()
            return if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
                ObjectMapper(YAMLFactory())
            } else {
                ObjectMapper()
            }
        }
    }
}
