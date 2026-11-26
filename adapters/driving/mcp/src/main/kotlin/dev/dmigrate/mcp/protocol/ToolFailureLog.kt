package dev.dmigrate.mcp.protocol

import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.core.error.ToolErrorCode
import org.slf4j.LoggerFactory

/**
 * Serverseitige Spur einer Ausnahme aus `tools/call`, bevor sie auf die redigierte Wire-Huelle abgebildet wird
 * (spec/mcp-server.md, Abschnitt Server-Log). Der Stacktrace geht als Text durch den [SecretScrubber]: Die
 * Ausnahme selbst an den Logger zu geben schriebe ihre Meldungen ungefiltert.
 */
internal object ToolFailureLog {

    private val LOG = LoggerFactory.getLogger(ToolFailureLog::class.java)

    fun record(failure: Throwable, code: ToolErrorCode, toolName: String, requestId: String) {
        if (code == ToolErrorCode.INTERNAL_AGENT_ERROR) {
            LOG.error("tool '{}' (req {}) failed with {}\n{}", toolName, requestId, code, scrubbedTrace(failure))
        } else if (LOG.isDebugEnabled) {
            LOG.debug("tool '{}' (req {}) failed with {}\n{}", toolName, requestId, code, scrubbedTrace(failure))
        }
    }

    private fun scrubbedTrace(failure: Throwable): String = SecretScrubber.scrub(failure.stackTraceToString())
}
