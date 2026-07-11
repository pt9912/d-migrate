package dev.dmigrate.server.adapter.audit.logging

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.ports.AuditSink
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Writes one INFO log line per [AuditEvent] to the `dev.dmigrate.audit`
 * logger. Output is a single-line JSON object (via [AuditEventJson]) so log
 * aggregators can parse it directly. `null` fields are omitted to keep
 * aggregator indexes clean. The persistent file variant is [JsonlFileAuditSink]
 * (LN-027) — both share [AuditEventJson] for byte-identical output.
 */
class LoggingAuditSink(
    private val logger: Logger = LoggerFactory.getLogger("dev.dmigrate.audit"),
) : AuditSink {

    override fun emit(event: AuditEvent) {
        if (!logger.isInfoEnabled) return
        logger.info(AuditEventJson.serialize(event))
    }
}
