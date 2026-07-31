package dev.dmigrate.server.adapter.audit.logging

import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.ports.AuditSink
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Hängt pro [AuditEvent] eine JSONL-Zeile an [file] an (LN-027 persistenter
 * Sink für CLI-DB-Operationen). Legt das Parent-Verzeichnis beim ersten Schreiben
 * an. Nutzt [AuditEventJson], damit die Zeilen byte-identisch zu [LoggingAuditSink]
 * sind. UTF-8, LF als Zeilenende.
 *
 * Die Datei wird beim ersten Schreiben mit `0600` angelegt (Befund 11, CWE-276) —
 * Audit-Zeilen können operations-sensible Metadaten tragen; sie dürfen nicht
 * world-readable entstehen.
 *
 * Der Sink selbst schluckt keine IO-Fehler — die Best-Effort-Semantik
 * (LN-027 E3) liegt beim `CliAuditRecorder`, damit ein Audit-Schreibfehler die
 * eigentliche Operation nicht abstürzen lässt.
 */
class JsonlFileAuditSink(private val file: Path) : AuditSink {

    override fun emit(event: AuditEvent) {
        file.parent?.let { Files.createDirectories(it) }
        ensureCreatedOwnerOnly()
        Files.writeString(
            file,
            AuditEventJson.serialize(event) + Char(LINE_FEED),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
    }

    /**
     * Legt die Datei — falls noch nicht vorhanden — mit `0600` **beim Anlegen** an
     * (kein chmod-Race), analog zum Credential-Store `writeAtomically`. Auf
     * Nicht-POSIX-Dateisystemen greift der Default-Pfad ohne Rechte-Attribut.
     * Eine bereits existierende Datei bleibt unberührt (Rechte sind Operator-Sache).
     */
    private fun ensureCreatedOwnerOnly() {
        if (Files.exists(file)) return
        try {
            if (posixSupported()) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_RW))
            } else {
                Files.createFile(file)
            }
        } catch (_: FileAlreadyExistsException) {
            // Ein nebenläufiger Writer hat die Datei angelegt — dessen Rechte gelten.
        }
    }

    private companion object {
        private const val LINE_FEED = 0x0A
        private val OWNER_RW = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)

        private fun posixSupported(): Boolean =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
    }
}
