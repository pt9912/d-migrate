package dev.dmigrate.server.adapter.audit.logging

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.ports.AuditSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Hängt pro [AuditEvent] eine JSONL-Zeile an [file] an (LN-027 persistenter
 * Sink für CLI-DB-Operationen). Legt das Parent-Verzeichnis beim ersten Schreiben
 * an. Nutzt [AuditEventJson], damit die Zeilen byte-identisch zu [LoggingAuditSink]
 * sind. UTF-8, LF als Zeilenende.
 *
 * Der Sink selbst schluckt keine IO-Fehler — die Best-Effort-Semantik
 * (LN-027 E3) liegt beim `CliAuditRecorder`, damit ein Audit-Schreibfehler die
 * eigentliche Operation nicht abstürzen lässt.
 */
class JsonlFileAuditSink(private val file: Path) : AuditSink {

    override fun emit(event: AuditEvent) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(
            file,
            AuditEventJson.serialize(event) + Char(LINE_FEED),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    private companion object {
        private const val LINE_FEED = 0x0A
    }
}
