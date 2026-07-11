package dev.dmigrate.cli.audit

import dev.dmigrate.cli.config.AuditSettingsResolver
import dev.dmigrate.cli.config.ResolvedAuditSettings
import dev.dmigrate.server.adapter.audit.logging.JsonlFileAuditSink
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.core.audit.AuditEvent
import dev.dmigrate.server.core.audit.AuditOutcome
import dev.dmigrate.server.ports.AuditSink
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * LN-027: Audit-Emitter für CLI-DB-Operationen. **Exit-code-getrieben** — anders
 * als `AuditScope` (MCP), das Outcome aus geworfenen Exceptions ableitet. Die
 * CLI-Runner fangen alles und geben `Int`-Exit-Codes zurück; [record] mappt Exit
 * `0 → SUCCESS`, `≠0 → FAILURE` und gibt den Code unverändert weiter.
 */
interface CliAuditRecorder {
    fun record(toolName: String, resourceRefs: List<String>, block: () -> Int): Int
}

/** Opt-out (`logging.audit.enabled: false`): ruft [block] direkt, emittiert nichts. */
object NoOpCliAuditRecorder : CliAuditRecorder {
    override fun record(toolName: String, resourceRefs: List<String>, block: () -> Int): Int = block()
}

/**
 * Emittiert pro Operation genau ein [AuditEvent]. `resourceRefs` werden per
 * [SecretScrubber] gescrubbt (E5). **Best-effort (E3):** ein Sink-Fehler wird
 * geloggt, aber verschluckt — er darf die Operation nicht abstürzen lassen.
 * Wirft der Block unerwartet, wird ein FAILURE-Event (`exitCode = null`) emittiert
 * und die Exception weitergereicht.
 */
class DefaultCliAuditRecorder(
    private val sink: AuditSink,
    private val clock: Clock = Clock.systemUTC(),
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) : CliAuditRecorder {

    override fun record(toolName: String, resourceRefs: List<String>, block: () -> Int): Int {
        val startedAt = Instant.now(clock)
        var exitCode: Int? = null
        try {
            val code = block()
            exitCode = code
            return code
        } finally {
            emit(toolName, resourceRefs, startedAt, exitCode)
        }
    }

    private fun emit(toolName: String, resourceRefs: List<String>, startedAt: Instant, exitCode: Int?) {
        val event = AuditEvent(
            requestId = requestId(),
            outcome = if (exitCode == 0) AuditOutcome.SUCCESS else AuditOutcome.FAILURE,
            startedAt = startedAt,
            toolName = toolName,
            resourceRefs = resourceRefs.map(SecretScrubber::scrub),
            durationMs = Instant.now(clock).toEpochMilli() - startedAt.toEpochMilli(),
            exitCode = exitCode,
        )
        try {
            sink.emit(event)
        } catch (ex: Exception) {
            LOG.warn("Audit-Event konnte nicht geschrieben werden (best-effort): {}", ex.message)
        }
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger("dev.dmigrate.audit")
    }
}

/**
 * Baut den Recorder aus den aufgelösten Settings: aktiv → [DefaultCliAuditRecorder]
 * über einen [JsonlFileAuditSink] auf `settings.file`; opt-out → [NoOpCliAuditRecorder].
 */
object CliAuditRecorders {
    fun fromSettings(settings: ResolvedAuditSettings): CliAuditRecorder =
        if (settings.enabled) DefaultCliAuditRecorder(JsonlFileAuditSink(settings.file)) else NoOpCliAuditRecorder
}

/**
 * Default-Recorder für ein Wiring: löst `logging.audit` aus der Config an
 * [configPath] auf und baut den passenden Recorder (LN-027). Wird als
 * Default-Parameter der Wiring-`execute`-Funktionen genutzt; Tests injizieren
 * stattdessen einen Fake.
 */
fun cliAuditRecorder(configPath: Path?): CliAuditRecorder =
    try {
        CliAuditRecorders.fromSettings(AuditSettingsResolver(configPathFromCli = configPath).resolve())
    } catch (ex: Exception) {
        // Best-effort (E3): eine unlesbare Audit-Config darf die Operation nicht
        // zusätzlich brechen. Echte Config-Fehler melden die operationseigenen
        // Resolver weiterhin — hier wird nur das Audit deaktiviert und gewarnt.
        LoggerFactory.getLogger("dev.dmigrate.audit")
            .warn("Audit-Konfiguration nicht lesbar, Audit deaktiviert: {}", ex.message)
        NoOpCliAuditRecorder
    }

/**
 * Wrappt [block] nur wenn [condition] zutrifft — für bedingt auditierte
 * Operationen (`schema compare` nur mit DB-Operand, `schema rollback` nur
 * `--execute`). Sonst wird [block] direkt ausgeführt (kein Event).
 */
fun CliAuditRecorder.recordIf(
    condition: Boolean,
    toolName: String,
    resourceRefs: List<String>,
    block: () -> Int,
): Int = if (condition) record(toolName, resourceRefs, block) else block()
