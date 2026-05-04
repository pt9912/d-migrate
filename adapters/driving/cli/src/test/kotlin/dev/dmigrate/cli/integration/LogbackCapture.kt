package dev.dmigrate.cli.integration

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.Logger.ROOT_LOGGER_NAME
import org.slf4j.LoggerFactory

/**
 * AP 6.24 E8(C) follow-up: in-process Logback capture so the
 * security-scrubbing canary can scan log output for raw secrets.
 * The cli module already depends on `logback-classic`, so the
 * cast from SLF4J's `ILoggerFactory` to Logback's `LoggerContext`
 * is safe in this test source-set.
 *
 * Use as `LogbackCapture.during { ... }` — attaches an in-memory
 * appender on the root logger for the duration of the block,
 * detaches in `finally`, and returns the captured events. Events
 * outside the block are not retained, so parallel-running specs
 * stay isolated as long as they each scope their own block.
 *
 * The capture targets every logger via the root level, but the
 * default Logback configuration is INFO+; if a future test needs
 * DEBUG-level lines it can pass a more permissive level.
 */
internal object LogbackCapture {

    fun <T> during(block: () -> T): CaptureResult<T> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = context.getLogger(ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            this.context = context
            start()
        }
        rootLogger.addAppender(appender)
        return try {
            val value = block()
            CaptureResult(value, appender.list.toList())
        } finally {
            rootLogger.detachAppender(appender)
            appender.stop()
        }
    }

    data class CaptureResult<T>(
        val value: T,
        val events: List<ILoggingEvent>,
    ) {
        /**
         * Renders every captured event as a single-line string in the
         * same form callers would see in stderr — formatter pattern,
         * level, message-with-arg-substitution, no MDC. This is what
         * the secret-scan in the canary test scans against.
         */
        fun renderedLines(): List<String> = events.map { ev ->
            "[${ev.level}] ${ev.loggerName}: ${ev.formattedMessage}"
        }
    }
}
