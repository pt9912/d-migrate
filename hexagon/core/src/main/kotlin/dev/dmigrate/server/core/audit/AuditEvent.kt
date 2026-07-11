package dev.dmigrate.server.core.audit

import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

enum class AuditOutcome { SUCCESS, FAILURE }

data class AuditEvent(
    val requestId: String,
    val outcome: AuditOutcome,
    val startedAt: Instant,
    val toolName: String? = null,
    val tenantId: TenantId? = null,
    val principalId: PrincipalId? = null,
    val errorCode: ToolErrorCode? = null,
    val payloadFingerprint: String? = null,
    val resourceRefs: List<String> = emptyList(),
    val durationMs: Long? = null,
    /**
     * Prozess-Exit-Code der Operation (LN-027 CLI-Audit). Die CLI ist
     * exit-code-getrieben, nicht exception-getrieben (`AuditScope` passt dort
     * nicht); der `CliAuditRecorder` setzt diesen Code, MCP-Events lassen ihn
     * `null`. `null` heißt „nicht anwendbar" (MCP-Pfad).
     */
    val exitCode: Int? = null,
)
