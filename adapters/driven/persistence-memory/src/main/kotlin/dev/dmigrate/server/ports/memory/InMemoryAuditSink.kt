package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.ports.AuditSink
import java.util.concurrent.CopyOnWriteArrayList

class InMemoryAuditSink : AuditSink {

    private val events = CopyOnWriteArrayList<AuditEvent>()

    override fun emit(event: AuditEvent) {
        events.add(event)
    }

    fun recorded(): List<AuditEvent> = events.toList()

    fun clear() {
        events.clear()
    }
}
