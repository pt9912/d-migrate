package dev.dmigrate.server.adapter.audit.logging

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.ports.AuditSink
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Writes one INFO log line per [AuditEvent] to the `dev.dmigrate.audit`
 * logger. Output is a single-line JSON object so log aggregators can
 * parse it directly. `null` fields are omitted to keep aggregator
 * indexes clean. Persistent sinks (DB, file) follow in Phase B.
 *
 * The JSON is hand-formatted to keep this module dependency-free
 * beyond SLF4J — no Jackson, no kotlinx.serialization.
 */
class LoggingAuditSink(
    private val logger: Logger = LoggerFactory.getLogger("dev.dmigrate.audit"),
) : AuditSink {

    override fun emit(event: AuditEvent) {
        if (!logger.isInfoEnabled) return
        logger.info(serialize(event))
    }

    private fun serialize(event: AuditEvent): String {
        val sb = StringBuilder(INITIAL_CAPACITY)
        sb.append('{')
        var first = true
        first = appendString(sb, first, "requestId", event.requestId)
        first = appendString(sb, first, "outcome", event.outcome.name)
        first = appendString(sb, first, "startedAt", event.startedAt.toString())
        first = appendOptionalString(sb, first, "toolName", event.toolName)
        first = appendOptionalString(sb, first, "tenantId", event.tenantId?.value)
        first = appendOptionalString(sb, first, "principalId", event.principalId?.value)
        first = appendOptionalString(sb, first, "errorCode", event.errorCode?.name)
        first = appendOptionalString(sb, first, "payloadFingerprint", event.payloadFingerprint)
        if (event.resourceRefs.isNotEmpty()) {
            first = appendArray(sb, first, "resourceRefs", event.resourceRefs)
        }
        event.durationMs?.let { appendNumber(sb, first, "durationMs", it) }
        sb.append('}')
        return sb.toString()
    }

    private fun appendString(sb: StringBuilder, first: Boolean, key: String, value: String): Boolean {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":\"").append(escape(value)).append('"')
        return false
    }

    private fun appendOptionalString(
        sb: StringBuilder,
        first: Boolean,
        key: String,
        value: String?,
    ): Boolean = if (value == null) first else appendString(sb, first, key, value)

    private fun appendArray(sb: StringBuilder, first: Boolean, key: String, values: List<String>): Boolean {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":[")
        values.forEachIndexed { index, value ->
            if (index > 0) sb.append(',')
            sb.append('"').append(escape(value)).append('"')
        }
        sb.append(']')
        return false
    }

    private fun appendNumber(sb: StringBuilder, first: Boolean, key: String, value: Long) {
        if (!first) sb.append(',')
        sb.append('"').append(key).append("\":").append(value)
    }

    private fun escape(value: String): String {
        val firstEscape = indexOfFirstEscape(value)
        if (firstEscape < 0) return value
        val sb = StringBuilder(value.length + ESCAPE_HEADROOM).append(value, 0, firstEscape)
        for (i in firstEscape until value.length) {
            val ch = value[i]
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch.code < 0x20 -> sb.append("\\u").append(ch.code.toString(HEX_RADIX).padStart(UNICODE_DIGITS, '0'))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun indexOfFirstEscape(value: String): Int {
        for (i in value.indices) {
            val ch = value[i]
            if (ch == '"' || ch == '\\' || ch.code < 0x20) return i
        }
        return -1
    }

    private companion object {
        private const val HEX_RADIX = 16
        private const val UNICODE_DIGITS = 4
        private const val INITIAL_CAPACITY = 256
        private const val ESCAPE_HEADROOM = 8
    }
}
