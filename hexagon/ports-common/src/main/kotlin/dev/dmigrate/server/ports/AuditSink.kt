package dev.dmigrate.server.ports

import dev.dmigrate.server.core.audit.AuditEvent

interface AuditSink {

    fun emit(event: AuditEvent)
}
